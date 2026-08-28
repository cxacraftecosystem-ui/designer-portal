package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDisplayImage
import com.designprototype.workshop.data.DwImageDecode
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * **"TRACE THIS PART OF THE PHOTOGRAPH" — the frame, drawn on the picture it belongs to.**
 *
 * The portal has had this since request 4 (`components/sketches/upload/FramePanel.tsx`) and the
 * handset has had nothing: the only thing a designer could choose was WHICH photograph. That gap is
 * worth closing on this client in particular, because a handset photograph of a sheet on a workbench
 * has the workbench in it — and every stage of the trace downstream reads global statistics off the
 * whole frame, so a dark table edge moves the threshold that decides whether a faint construction
 * line survives.
 *
 * ── WHAT THIS CHANGES, AND WHAT IT DOES NOT ───────────────────────────────────────────────────
 *
 * `DwSketchTraceCrop.kt`'s header holds the argument in full and it is the reason this panel can
 * exist at all: **the photograph on the record is never altered, never re-encoded and never
 * replaced.** The frame is a TRACE INPUT. It changes the drawing that lands in the line-art field, it
 * is recorded in that drawing's provenance note, and it produces no file of its own.
 *
 * Two consequences are visible here rather than in the arithmetic:
 *
 *  1. **THE ORIGINAL FILE IS DECODED AFRESH FOR EVERY RUN.** Nothing in this panel ever replaces the
 *     decode with a cropped one, so a designer who pulled the box too far in can always widen it
 *     again. A crop tool that cannot be undone is a crop tool people do not use.
 *  2. **The box on screen and the box being traced are two different things**, and the panel says so
 *     whenever they differ. One button commits. That is `FramePanel.tsx`'s decision and it holds
 *     harder here: a full trace on a handset is seconds to tens of seconds of one core, so a live
 *     crop would start one on every finger movement.
 *
 * ── THE ROUTES IN, AND WHY THE NUMBERS ARE THE PRIMARY ONE ────────────────────────────────────
 *
 * `RankableList.tsx` states the rule both clients follow: *"a drag is a pointer gesture and is
 * unreachable from a keyboard, from a switch device and from a screen reader."* The portal answers
 * that with four number inputs, four keyboard-reachable handles and a pointer drag. A handset has no
 * keyboard to give a handle, so the two routes here are:
 *
 *  · **FOUR NUMBER BOXES — Left, Top, Width, Height, in the photograph's own pixels.** The route that
 *    works with TalkBack, with a switch device, and for anybody whose hands are not steady enough to
 *    place a 16 dp handle. It is also the only route that can be READ OUT, which is why a number that
 *    had to be clamped announces itself instead of silently becoming something else.
 *  · **FOUR HANDLES AND A DRAG ON THE BOX.** The fast route for the person holding the sheet.
 *
 * ── WHICH PIXELS THE NUMBERS ARE IN ───────────────────────────────────────────────────────────
 *
 * The frame the designer aims in is **the frame the engine will be handed** — [dwTraceWorkingSize] of
 * the photograph's own dimensions, which is at most 4096 on the long edge. Not the preview's pixels,
 * which are a tenth of that and would make every number in the provenance note meaningless; and not
 * the stored file's, which the engine never sees. So the four numbers on screen are the four numbers
 * in the exported drawing's provenance sentence, and there is one coordinate system to reason about.
 *
 * ── COLLAPSED UNTIL ASKED FOR ─────────────────────────────────────────────────────────────────
 *
 * `DwSketchTracePanel`'s own rule — every large allocation lives inside the open half — and this
 * panel's allocation is a decoded preview of a photograph on a phone that is also holding two trace
 * plates and possibly a camera. Closed, it is one line that says what the trace is using, which is the
 * fact a designer needs when they are not framing anything.
 */

/**
 * The long edge the framing preview is decoded to.
 *
 * Big enough to aim at and small enough to be free: a 4:3 frame at this ceiling is 1024 by 768, which
 * is 1.6 MB at the RGB_565 `DwImageDecode.decodeForDisplay` pins — against 6 MB at its own 2400 px
 * default, on a handset already holding two 1024 px ARGB plates at 4.2 MB each. It is the same number
 * as `DW_TRACE_PLATE_LONG_EDGE_PX` for a reason that is not a coincidence: both are pictures shown
 * inside a panel a few hundred density-independent pixels wide, and neither is measured in.
 *
 * It is NOT what bounds a drag's precision, and it would be easy to write that here and be wrong: the
 * box is moved in screen pixels, so what bounds a drag is the width the frame is drawn at — a few
 * hundred density-independent pixels of a photograph up to 4096 wide, which is several source pixels
 * per finger-pixel however careful the person is. This ceiling only has to be sharp enough to SEE the
 * edge of the sheet. The route that places a frame exactly is the four number boxes, which is why
 * they are the primary one.
 */
const val DW_TRACE_CROP_PREVIEW_EDGE_PX: Int = 1024

/**
 * The frame chooser.
 *
 * @param sourcePath the photograph on the record, as a file this device holds.
 * @param sourceKey the media id — the decode is keyed on it, because a frame chosen on one sheet is
 *   meaningless on the next and leaving it applied would trace a region of a photograph nobody framed.
 * @param applied what the trace is currently using, or null for the whole photograph.
 * @param onApply called only from a button the designer pressed. Null means the whole photograph.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwTraceFramePanel(
    sourcePath: String,
    sourceKey: String,
    applied: DwTraceFrameChoice?,
    enabled: Boolean,
    onApply: (DwTraceFrameChoice?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Crop,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "The part of the photograph to trace",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    // TRUE WHETHER OR NOT THIS IS OPEN. A closed control that says nothing about its
                    // own state is a control a designer has to open to find out whether they touched
                    // it, and this one changes what the drawing IS.
                    if (applied == null) {
                        "The whole photograph."
                    } else {
                        "${applied.rect.width}×${applied.rect.height} of " +
                            "${applied.frameWidth}×${applied.frameHeight}."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            OutlinedButton(
                onClick = { open = !open },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (open) "Done" else "Choose a frame", fontSize = 12.sp)
            }
        }

        if (open) {
            DwTraceFrameChooser(
                sourcePath = sourcePath,
                sourceKey = sourceKey,
                applied = applied,
                enabled = enabled,
                onApply = onApply,
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The open half
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The decode, and the two things to say while there is nothing to draw on.
 *
 * SPLIT FROM THE EDITOR BELOW, which is `DwSketchTracePanel`'s own rule and its reason: a composable
 * whose remembered slots sit below a conditional return appears and disappears between frames. Here
 * the state that must survive a recomposition — the box, the draft, the clamp sentence — belongs to a
 * photograph that has actually been decoded, so it lives in the composable that only exists once one
 * has been.
 */
@Composable
private fun DwTraceFrameChooser(
    sourcePath: String,
    sourceKey: String,
    applied: DwTraceFrameChoice?,
    enabled: Boolean,
    onApply: (DwTraceFrameChoice?) -> Unit,
) {
    var image by remember(sourceKey) { mutableStateOf<DwDisplayImage?>(null) }
    var decoding by remember(sourceKey) { mutableStateOf(true) }

    LaunchedEffect(sourceKey) {
        decoding = true
        val decoded = withContext(Dispatchers.Default) {
            DwImageDecode.decodeForDisplay(sourcePath, DW_TRACE_CROP_PREVIEW_EDGE_PX)
        }
        image = decoded
        decoding = false
    }

    val decoded = image
    when {
        decoded != null -> DwTraceFrameEditor(
            decoded = decoded,
            sourceKey = sourceKey,
            applied = applied,
            enabled = enabled,
            onApply = onApply,
        )

        decoding -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Opening the photograph…", color = MaterialTheme.field.muted, fontSize = 12.sp)
        }

        else -> DwPanelNote(
            warning = true,
            // The trace itself may still work — it uses a different decoder with a different ceiling
            // — so this refuses the FRAMING and says nothing about the drawing.
            text = "This phone could not open that photograph to show it here, so the frame cannot " +
                "be chosen on this device. The whole photograph is what gets traced.",
            polite = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTraceFrameEditor(
    decoded: DwDisplayImage,
    sourceKey: String,
    applied: DwTraceFrameChoice?,
    enabled: Boolean,
    onApply: (DwTraceFrameChoice?) -> Unit,
) {
    var rect by remember(sourceKey) { mutableStateOf<DwTraceCropRect?>(null) }
    var clampNote by remember(sourceKey) { mutableStateOf("") }
    var draft by remember(sourceKey) { mutableStateOf<Pair<String, String>?>(null) }

    /*
      THE COORDINATE SYSTEM, DECIDED ONCE. Every number in this panel is in the frame the ENGINE will
      be handed — `dwTraceWorkingSize` of the photograph's own dimensions — and never in the preview's
      pixels. `DwDisplayImage.sourceWidth` is the original frame with any EXIF rotation already
      applied, which is the same frame `decodeForTrace` produces: that path refuses a rotation-tagged
      file outright, so for anything this panel can usefully frame the two agree.
    */
    val (frameWidth, frameHeight) = remember(decoded) {
        dwTraceWorkingSize(decoded.sourceWidth, decoded.sourceHeight)
    }
    val preview: ImageBitmap = remember(decoded) { decoded.bitmap.asImageBitmap() }

    val box = rect ?: dwTraceWholeFrame(frameWidth, frameHeight)
    fun setBox(next: DwTraceCropRect) {
        rect = dwTraceClampCrop(next, frameWidth, frameHeight)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        DwTraceCropOverlay(
            preview = preview,
            box = box,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            enabled = enabled,
            onBox = { setBox(it) },
        )

        /* ── The numbers, which are the primary route ───────────────────────────────────────── */

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DW_TRACE_CROP_FIELDS.forEach { field ->
                DwTraceCropNumberField(
                    label = field,
                    value = when (field) {
                        "Left" -> box.x
                        "Top" -> box.y
                        "Width" -> box.width
                        else -> box.height
                    },
                    draft = draft?.takeIf { it.first == field }?.second,
                    enabled = enabled,
                    onType = { draft = field to it },
                    onCommit = { text ->
                        draft = null
                        val typed = text.trim().toIntOrNull()
                        if (typed == null) {
                            // AN EMPTY OR UNREADABLE BOX RESTORES THE FRAME AND SAYS NOTHING. Clearing
                            // a field in order to retype it is not a mistake to report, and reading it
                            // as zero would silently jump the frame to a corner.
                            clampNote = ""
                        } else {
                            val candidate = when (field) {
                                "Left" -> box.copy(x = typed)
                                "Top" -> box.copy(y = typed)
                                "Width" -> box.copy(width = typed)
                                else -> box.copy(height = typed)
                            }
                            val next = dwTraceClampCrop(candidate, frameWidth, frameHeight)
                            setBox(next)
                            val kept = when (field) {
                                "Left" -> next.x
                                "Top" -> next.y
                                "Width" -> next.width
                                else -> next.height
                            }
                            clampNote = if (kept == typed) {
                                ""
                            } else {
                                dwTraceCropClampNote(field, typed, next, frameWidth, frameHeight)
                            }
                        }
                    },
                )
            }
        }

        if (clampNote.isNotEmpty()) {
            // POLITE AND NOT A WARNING BANNER: it is the consequence of an ordinary edit, and an
            // assertive interruption on every committed number would be worse than the silence it
            // replaces.
            DwPanelNote(warning = false, text = clampNote, polite = true)
        }

        Text(
            "${dwTraceCropReadout(box, frameWidth, frameHeight)} $DW_TRACE_CROP_ENGINE_NOTE",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        /* ── Committing ─────────────────────────────────────────────────────────────────────── */

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onApply(
                        if (dwTraceIsWholeFrame(box, frameWidth, frameHeight)) {
                            null
                        } else {
                            DwTraceFrameChoice(box, frameWidth, frameHeight)
                        },
                    )
                },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use this frame for the trace", fontSize = 12.sp)
            }
            if (applied != null || !dwTraceIsWholeFrame(box, frameWidth, frameHeight)) {
                OutlinedButton(
                    onClick = {
                        setBox(dwTraceWholeFrame(frameWidth, frameHeight))
                        clampNote = ""
                        onApply(null)
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Use the whole photograph", fontSize = 12.sp)
                }
            }
        }

        /*
          THE STALE SENTENCE. A control whose effect has silently gone stale is indistinguishable from
          a control that does nothing, which is the bug class this repository takes most seriously —
          and here the two states differ by which pixels are in the ministry's drawing.
        */
        val onScreen = if (dwTraceIsWholeFrame(box, frameWidth, frameHeight)) null else box
        if (applied?.rect != onScreen) {
            DwPanelNote(
                warning = false,
                text = if (applied == null) {
                    "Nothing has been applied yet: the trace is still using the whole photograph."
                } else {
                    dwTraceCropStaleNote(applied.rect)
                },
                polite = true,
            )
        }
    }
}

/** The four boxes, in the order they are drawn. One list, so nothing can fall out of step with it. */
private val DW_TRACE_CROP_FIELDS = listOf("Left", "Top", "Width", "Height")

/* ────────────────────────────────────────────────────────────────────────────
 * The picture and the box on it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The photograph with the frame drawn over it, and four handles on the frame.
 *
 * ── THE SURROUND IS DIMMED RATHER THAN THE INSIDE BRIGHTENED ──────────────────────────────────
 *
 * What is being judged is what stays, so what stays is shown as it is. Four rectangles and not one
 * cut-out shape, because four `drawRect` calls need no path and no clip layer — on the phones this
 * programme uses, a saved clip layer per frame during a drag is the difference between a box that
 * follows the finger and one that lags it.
 *
 * ── A DRAG IS ACCUMULATED IN FRACTIONS AND APPLIED IN WHOLE PIXELS ────────────────────────────
 *
 * A frame pixel is smaller than a screen pixel here — a 4096 px photograph shown 1024 px wide — so
 * rounding every touch delta on its own would throw away most of a slow drag and the box would refuse
 * to move at all. The remainder is carried between events instead, so the box tracks the finger and
 * every value it takes is still a whole pixel.
 */
@Composable
private fun DwTraceCropOverlay(
    preview: ImageBitmap,
    box: DwTraceCropRect,
    frameWidth: Int,
    frameHeight: Int,
    enabled: Boolean,
    onBox: (DwTraceCropRect) -> Unit,
) {
    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }

    val aspect = if (preview.height > 0) preview.width.toFloat() / preview.height.toFloat() else 1f
    val toFrameX = if (viewWidth > 0f) frameWidth / viewWidth else 0f
    val toFrameY = if (viewHeight > 0f) frameHeight / viewHeight else 0f
    val toViewX = if (frameWidth > 0) viewWidth / frameWidth else 0f
    val toViewY = if (frameHeight > 0) viewHeight / frameHeight else 0f

    val density = LocalDensity.current
    // The drag target for the whole box, sized in density-independent units because a `Modifier.size`
    // takes them. Half a handle, in raw pixels, is what the four corner offsets are centred by.
    val boxWidthDp = with(density) { (box.width * toViewX).toDp() }
    val boxHeightDp = with(density) { (box.height * toViewY).toDp() }
    val half = with(density) { 22.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(MaterialTheme.field.surface200, RoundedCornerShape(8.dp))
            .onSizeChanged {
                viewWidth = it.width.toFloat()
                viewHeight = it.height.toFloat()
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            drawImage(
                image = preview,
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            val left = box.x * toViewX
            val top = box.y * toViewY
            val right = (box.x + box.width) * toViewX
            val bottom = (box.y + box.height) * toViewY
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset(0f, 0f), Size(size.width, top))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
            // Two strokes, light over dark, so the edge is visible against a white sheet and against a
            // dark workbench without depending on either — the seam in `DwSketchTraceCompare` is drawn
            // the same way for the same reason.
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = Offset(left - 1f, top - 1f),
                size = Size((right - left) + 2f, (bottom - top) + 2f),
                style = Stroke(width = 3f),
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 1.5f),
            )
        }

        // Dragging the BOX moves it whole. Placed under the handles so a finger on a corner resizes
        // rather than slides — the corner is the smaller target and therefore the deliberate one.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((box.x * toViewX).roundToInt(), (box.y * toViewY).roundToInt())
                }
                .size(width = boxWidthDp, height = boxHeightDp)
                .pointerInput(enabled, frameWidth, frameHeight, toFrameX, toFrameY) {
                    if (!enabled) return@pointerInput
                    var carryX = 0f
                    var carryY = 0f
                    detectDragGestures(
                        onDragStart = { carryX = 0f; carryY = 0f },
                    ) { change, drag ->
                        change.consume()
                        carryX += drag.x * toFrameX
                        carryY += drag.y * toFrameY
                        val stepX = carryX.toInt()
                        val stepY = carryY.toInt()
                        if (stepX != 0 || stepY != 0) {
                            carryX -= stepX
                            carryY -= stepY
                            onBox(dwTraceMoveCrop(box, stepX, stepY, frameWidth, frameHeight))
                        }
                    }
                }
                .semantics {
                    contentDescription = "The frame that will be traced. Drag it to move it; the " +
                        "four boxes below set it exactly."
                },
        )

        DwTraceCropCorner.entries.forEach { corner ->
            val atRight = corner == DwTraceCropCorner.TOP_RIGHT || corner == DwTraceCropCorner.BOTTOM_RIGHT
            val atBottom = corner == DwTraceCropCorner.BOTTOM_LEFT || corner == DwTraceCropCorner.BOTTOM_RIGHT
            val cx = (if (atRight) box.x + box.width else box.x) * toViewX
            val cy = (if (atBottom) box.y + box.height else box.y) * toViewY
            Box(
                modifier = Modifier
                    .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
                    // 44 dp of TARGET around a 14 dp mark. Material's minimum is 48 dp and this is
                    // under it deliberately: four 48 dp targets on a small frame overlap in the middle
                    // of a box a designer is trying to pull to a corner, and an overlapping target is
                    // a corner that moves the wrong way. The number boxes are the route for anybody
                    // this is too small for, which is why they are the primary one.
                    .size(44.dp)
                    .pointerInput(enabled, frameWidth, frameHeight, toFrameX, toFrameY) {
                        if (!enabled) return@pointerInput
                        var carryX = 0f
                        var carryY = 0f
                        detectDragGestures(
                            onDragStart = { carryX = 0f; carryY = 0f },
                        ) { change, drag ->
                            change.consume()
                            carryX += drag.x * toFrameX
                            carryY += drag.y * toFrameY
                            val stepX = carryX.toInt()
                            val stepY = carryY.toInt()
                            if (stepX != 0 || stepY != 0) {
                                carryX -= stepX
                                carryY -= stepY
                                onBox(
                                    dwTraceMoveCorner(
                                        box, corner, stepX, stepY, frameWidth, frameHeight,
                                    ),
                                )
                            }
                        }
                    }
                    .semantics { contentDescription = dwTraceCornerName(corner) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/** What TalkBack calls a handle. Positional, because the position is the only thing it means. */
private fun dwTraceCornerName(corner: DwTraceCropCorner): String = when (corner) {
    DwTraceCropCorner.TOP_LEFT -> "Top-left corner of the frame. Drag it, or use the boxes below."
    DwTraceCropCorner.TOP_RIGHT -> "Top-right corner of the frame. Drag it, or use the boxes below."
    DwTraceCropCorner.BOTTOM_LEFT -> "Bottom-left corner of the frame. Drag it, or use the boxes below."
    DwTraceCropCorner.BOTTOM_RIGHT -> "Bottom-right corner of the frame. Drag it, or use the boxes below."
}

/**
 * One of the four number boxes.
 *
 * **THE HALF-TYPED NUMBER IS THE WHOLE PROBLEM HERE.** Committing on every keystroke means that
 * typing "1200" into Width passes through 1, 12 and 120 — each of which is a legal crop, each of
 * which re-clamps the origin, and the box on screen jumps three times before the designer has
 * finished the number they meant. So the characters being typed are held as a draft and the frame is
 * only touched when the field is left or the keyboard's Done is pressed, which is
 * `FramePanel.tsx:665-690`'s arrangement in Compose's dialect.
 */
@Composable
private fun DwTraceCropNumberField(
    label: String,
    value: Int,
    draft: String?,
    enabled: Boolean,
    onType: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    val shown = draft ?: value.toString()
    Column(modifier = Modifier.width(150.dp)) {
        DwPanelLabel(label)
        OutlinedTextField(
            value = shown,
            onValueChange = onType,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit(shown) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused && draft != null) onCommit(draft) },
        )
    }
}
