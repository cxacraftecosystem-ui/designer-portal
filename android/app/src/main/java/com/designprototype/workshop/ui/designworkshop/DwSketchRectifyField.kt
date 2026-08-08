package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
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
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt for why a
// bare Material `Text` here would quietly set this panel's headings in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Straighten a photographed sketch into a plate" — the handset's surface over [DwSketchRectify].
 *
 * ── WHY THIS IS ON THE PHONE ──────────────────────────────────────────────────────────────────
 *
 * Stage 11's `sketch.image` is required, and the way a sketch enters this archive is that somebody
 * photographs a sheet of paper lying on a table, at whatever angle they were standing, under a tube
 * light on one wall. The report then prints exactly that: a skewed grey trapezoid of paper with a
 * drawing somewhere inside it. Rectified and thresholded it is a plate — the sheet square to the
 * page, the pencil black, the paper white.
 *
 * The phone is where the photograph is TAKEN, and that is the whole argument for the panel being
 * here rather than only on the web. A designer who can see that the plate lost the faint construction
 * lines is the designer standing in the room with the original sheet still in front of them, and the
 * remedy — photograph it again with the lamp somewhere else — costs ten seconds now and is impossible
 * four days later at a desk. [DwSketchRectify]'s header sets out what this adds over the crop tool
 * already on the handset.
 *
 * ── IT NEVER TOUCHES THE PHOTOGRAPH, AND THE FIELD IT SITS ON IS WHY ──────────────────────────
 *
 * The panel is offered on the DESTINATION field (`sketch.lineArtFile`), never on `sketch.image`, and
 * that placement is load-bearing rather than tidy. A single-valued IMAGE field REPLACES its value
 * when something is attached to it — [DwMediaCaptureCard]'s `onIdsChange` keeps `newIds.first()` for
 * a non-list field — so a panel that attached a rectified plate to `image` would DETACH the original
 * photograph from the record, which is precisely what docs/MEDIA_PIPELINE.md §5 refuses. Offering it
 * on the destination means the plate is written where a plate belongs and the photograph is only ever
 * read. This mirrors `stageFieldRoles.offersSketchRectify` on the web, for the same reason.
 *
 * ── NOTHING IS ATTACHED WITHOUT THE DESIGNER SEEING IT FIRST ──────────────────────────────────
 *
 * Two buttons, in order: one makes a plate and shows it, the other attaches the plate that is on
 * screen. There is no path from a decode to an attachment that does not pass through a person
 * looking at the result — the same discipline [DwPhotoMeasurePanel] and [DwIdentityOcrControl] apply
 * to a proposed number, and it matters more here because a plate is a picture that will be printed
 * and a bad threshold is obvious to a human and invisible to everything else.
 *
 * THE FOUR HANDLES ARE NOT GATED ON BEING "PLACED", and that is a deliberate difference from
 * [DwPhotoMeasurePanel], which refuses to show a measurement until every mark has been moved. There,
 * a reading off the default layout would be a confident NUMBER about nothing, with nothing on screen
 * to contradict it. Here the seeded inset rectangle is a perfectly legitimate quadrilateral — for a
 * photograph that already fills the frame it is close to right — and the output is a PICTURE the
 * designer is looking at. The preview is the check that the gate would have been.
 *
 * ── NO AUTOMATIC CORNER GUESS, AND [DwSketchRectify]'s HEADER ARGUES THAT AT LENGTH ───────────
 *
 * The web module can propose the four corners by finding the largest bright region. It is not ported.
 * Its own comment calls the manual path the real feature and the guess a convenience on top of it —
 * and the manual path is what is built here, complete, including the nudge arrows that are the only
 * route to a corner for somebody who cannot aim a fingertip.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Which field gets the offer
 * ──────────────────────────────────────────────────────────────────────────── */

/** Keys and labels folded to letters, so `lineArtFile` and "Line art / vector file" both match. */
private fun dwFolded(value: String): String =
    value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }

/**
 * What a FILE field has to be called before it is treated as the home of a derived plate.
 *
 * THIS GUESSES, and the guess is allowed to be wrong because a wrong answer is cheap in both
 * directions — the same bargain the five other field roles in this app strike. A false positive
 * offers a straightening panel on a file field that holds something else; it writes nothing until a
 * designer presses a button, and they can simply not press it. A false negative leaves the ordinary
 * file picker, which still works. Verbatim from `stageFieldRoles.LINE_ART_KEY` so the two clients
 * offer the panel in the same places.
 */
private val DW_LINE_ART_KEY = Regex("(lineart|linedrawing|vector|plate|artwork)")

/**
 * The image fields on this entity a plate could be made from, in the order the form renders them.
 *
 * EVERY non-deprecated image field qualifies, deliberately, for the same reason
 * [dwOffersPhotoMeasure] does not try to pick one: nothing here can tell which photograph is of a
 * sheet of paper and which is of the artisan holding it. That is a fact about what somebody pointed a
 * camera at thirty seconds ago, and a designer choosing from a list of their own photographs is both
 * cheaper and more reliable than any guess this file could make.
 */
internal fun dwSketchSourceFields(siblings: Map<String, FieldDto>): List<FieldDto> =
    siblings.values.filter { candidate ->
        if (candidate.deprecated) return@filter false
        val type = DwFieldType.of(candidate.type)
        type == DwFieldType.IMAGE || type == DwFieldType.IMAGE_LIST
    }

/**
 * Should this FILE field offer "straighten a photographed sketch into a plate"?
 *
 * TWO CONDITIONS, AND THE SECOND IS THE ONE THAT MATTERS. The field must look like the destination
 * for a plate, AND the entity must actually have an image field to make one FROM. Stage 11's `sketch`
 * entity is the case this was written for: it declares `image` (required, the photograph of the
 * sheet) and `lineArtFile` ("An SVG or vector export, if one was produced"), which is exactly the
 * pairing — a photograph to read and a separate slot to put the derived plate in.
 *
 * TWO ENTITIES QUALIFY IN THE BUNDLED REGISTRY, not one, and the second is worth naming because it
 * looks at first like a false positive: stage 16's `finalProduct` declares `lineDrawing`
 * ("Vector / line drawing") beside three image fields. A line drawing for a finished product is often
 * a CAD export, which this panel has nothing to do with — but it is just as often a technical drawing
 * made on paper and photographed, which is exactly this feature. Offering it there costs a panel
 * nobody presses; withholding it would hide the feature from a designer who did photograph a drawing.
 * The web makes the same call from the same regex (`stageFieldRoles.offersSketchRectify`), and the
 * two clients offering it in different places would be worse than either choice.
 */
internal fun dwOffersSketchRectify(field: FieldDto, siblings: Map<String, FieldDto>): Boolean {
    if (field.deprecated) return false
    if (DwFieldType.of(field.type) != DwFieldType.FILE) return false
    if (!DW_LINE_ART_KEY.containsMatchIn(dwFolded("${field.key} ${field.label}"))) return false
    return dwSketchSourceFields(siblings).isNotEmpty()
}

/** One photograph the panel may read, and which image field it is attached to. */
@Immutable
internal data class DwSketchSource(val fieldLabel: String, val item: DwMediaItem)

/* ────────────────────────────────────────────────────────────────────────────
 * Choices
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A declared sheet proportion, or null for "whatever the four marks imply".
 *
 * A3 IS NOT A SEPARATE CHOICE, and saying so out loud is more useful than offering it twice: every
 * A-series sheet has the same 1:√2 proportion by construction, so A3, A4 and A5 are one button. The
 * numbers are written as the millimetre sizes rather than as √2 because that is what is printed on
 * the pad the designer is holding, and 210/297 is what `rectifiedSize` actually receives.
 */
private data class DwSheetShape(val label: String, val aspect: Double?)

private val SHEET_SHAPES = listOf(
    DwSheetShape("As marked", null),
    DwSheetShape("A-series portrait (A3/A4/A5)", 210.0 / 297.0),
    DwSheetShape("A-series landscape", 297.0 / 210.0),
)

private const val SKETCH_MIN_ZOOM = 1f

/** As [DwPhotoMeasurePanel]: past this the screen shows interpolation rather than photograph. */
private const val SKETCH_MAX_ZOOM = 12f

/** Where the four handles start, as a fraction of the working copy. An inset rectangle, not a guess. */
private val CORNER_SEEDS = listOf(
    0.12 to 0.12,
    0.88 to 0.12,
    0.88 to 0.88,
    0.12 to 0.88,
)

private val CORNER_BADGE = listOf("1", "2", "3", "4")

/**
 * What each handle is CALLED to a screen reader, and why not one of these four names says "top-left".
 *
 * THE NAMES USED TO CLAIM AN ORIENTATION AND NOTHING KEPT THAT CLAIM TRUE. The handles are never
 * re-ordered: [DwSketchRectify.orderCorners] runs once, inside `build()`, on a throwaway copy, so the
 * PLATE is always right and the INDICES never move. Meanwhile this panel invites the designer to
 * "tap the photograph to place the selected handle", and tapping the four corners of a sheet in
 * reading order — top-left, top-right, BOTTOM-LEFT, bottom-right — is the exact mistake
 * [DwSketchRectify.orderCorners] documents at length as the natural one. Do that and handle 3 sits on
 * the bottom-left corner while announcing "Bottom-right corner of the sheet", and handle 4 announces
 * the reverse. The plate is still correct, so nothing downstream ever contradicts the two false
 * sentences — and the person they are read to is the one using [DwSketchNudgePad], which this file's
 * header calls the only route to a corner for somebody who cannot aim a fingertip.
 *
 * VERBATIM FROM THE WEB PANEL, whose handles are labelled `Sheet corner ${index + 1}` and make no
 * positional claim either. That panel keeps the orientation honest a second way — `endDrag` re-runs
 * `orderCorners` over the live handles on pointer-up — which is not an option here: this panel has an
 * ACTIVE handle that the corner chips and the nudge arrows both point at, and silently re-labelling
 * on release would move the nudge pad onto a different corner between one press and the next.
 */
private val CORNER_NAME = listOf(
    "Sheet corner 1",
    "Sheet corner 2",
    "Sheet corner 3",
    "Sheet corner 4",
)

/* ────────────────────────────────────────────────────────────────────────────
 * The panel
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The whole surface, collapsed until the designer asks for it.
 *
 * COLLAPSED BY DEFAULT AND EVERY LARGE ALLOCATION LIVES INSIDE THE OPEN HALF, deliberately: closing
 * the panel drops the decoded working copy, its luma plane and the plate — together the largest
 * allocation this feature makes — and re-opening decodes again off the main thread. That is the right
 * trade on a phone whose other job right now is the camera.
 *
 * Split into two composables for the reason [DwIdentityOcrControl]'s comment gives: a composable
 * whose remembered slots sit below a conditional return appears and disappears between frames.
 */
@Composable
internal fun DwSketchRectifyPanel(
    field: FieldDto,
    sources: List<DwSketchSource>,
    media: DwMediaBridge,
    /** The file already in this field, so the panel can say what attaching would replace. */
    currentFileName: String?,
    enabled: Boolean,
    /** Write the new media id into this FILE field. Called only from a button the designer pressed. */
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Straighten a photographed sketch into a plate",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Mark the four corners of the sheet in one of the photographs on this record and the " +
                    "paper is squared to the page, with the pencil brought out as black line on white. " +
                    "The result is attached here as ${field.label}; the photograph itself is never " +
                    "changed. It runs on this device and needs no connection.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Straighten a sketch", fontSize = 13.sp)
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
    onClose: () -> Unit,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceId by remember { mutableStateOf(sources.first().item.id) }
    val source = sources.firstOrNull { it.item.id == sourceId } ?: sources.first()

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
    /*
     * The platform bitmap is kept BESIDE the Compose one rather than derived from it when the attach
     * button is pressed. `ImageBitmap.asAndroidBitmap()` exists, but it is a cast that only holds for
     * a bitmap this code created — and the pixels the designer approved and the pixels that get
     * encoded must be the same object, not two views that a future refactor could let diverge.
     */
    var plateBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var plateNote by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    /**
     * Decode the chosen photograph and take its luma plane, off the main thread, one at a time.
     *
     * `Dispatchers.Default` and never the main thread: [DwImageDecode] measures its own decode at a
     * few hundred milliseconds and sometimes approaching a second on a loaded handset, which on the
     * main thread is an unmistakable freeze. Keyed on the photograph's id, so switching photographs
     * releases the previous working copy before the next is decoded rather than holding two.
     *
     * THE CORNERS AND THE PLATE ARE CLEARED WITH IT. A corner is a position on ONE photograph in ONE
     * working copy's pixel grid, and a plate on screen beside a different photograph is a picture the
     * designer would attach believing it came from what they are looking at.
     */
    LaunchedEffect(source.item.id) {
        image = null
        sourcePlane = null
        plate = null
        plateBitmap = null
        plateNote = null
        refusal = null
        corners.clear()
        active = 0
        decoding = true
        val decoded = withContext(Dispatchers.Default) { DwImageDecode.decodeForDisplay(source.item.absolutePath) }
        // The plane is taken ONCE here rather than on every press of "Make the plate": it is a read of
        // every pixel of the working copy, it cannot change while the photograph does not, and the
        // designer will press that button several times while trying the two output modes.
        val grey = decoded?.let { withContext(Dispatchers.Default) { DwSketchPlate.greyPlaneOf(it.bitmap) } }
        decoding = false
        image = decoded
        sourcePlane = grey
    }

    val bitmap: ImageBitmap? = remember(image) { image?.bitmap?.asImageBitmap() }
    val imageWidth = image?.bitmap?.width?.toFloat() ?: 0f
    val imageHeight = image?.bitmap?.height?.toFloat() ?: 0f

    /** Screen pixels per working-copy pixel at zoom 1 — "fit the whole photograph in the box". */
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

    /** Seed the four handles once the working copy's size is known. An inset rectangle, never a guess. */
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
        // Clamped to the photograph. A corner dragged past the edge is a coordinate outside the image,
        // and while `bilinearSample` would honestly return paper white for it, a handle the designer
        // cannot see is a handle they cannot get back — it would sit off-screen with the plate quietly
        // wrong. Somebody whose sheet really was photographed cut off marks the corner ON the edge,
        // which is the same quadrilateral to within a pixel.
        corners[index] = DwPoint(
            x = min(imageWidth.toDouble(), max(0.0, point.x)),
            y = min(imageHeight.toDouble(), max(0.0, point.y)),
        )
        // The plate on screen was made from the corners as they were. Leaving it up beside a moved
        // handle is how somebody attaches a plate that does not match the marks they can see.
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

    fun build() {
        val plane = sourcePlane ?: return
        val marked = (0 until 4).mapNotNull { corners[it] }
        if (marked.size != 4) return
        working = true
        plate = null
        plateBitmap = null
        plateNote = null
        refusal = null
        scope.launch {
            // Ordered before anything is solved, so a designer who dragged corner 3 past corner 4
            // gets the sheet they can see rather than a homography that folds the plane through
            // itself. See [DwSketchRectify.orderCorners].
            val ordered = DwSketchRectify.orderCorners(marked)
            if (ordered == null) {
                working = false
                refusal = "Those four handles do not enclose a sheet. Drag them to the corners of the paper."
                return@launch
            }
            val outcome = withContext(Dispatchers.Default) {
                DwSketchRectify.makePlate(
                    plane,
                    ordered,
                    DwPlateOptions(aspect = shape.aspect, lineArt = lineArt),
                )
            }
            when (outcome) {
                is DwPlateResult.Refusal -> {
                    working = false
                    refusal = outcome.reason
                }

                is DwPlateResult.Plate -> {
                    val rendered = withContext(Dispatchers.Default) { DwSketchPlate.bitmapOf(outcome.plane) }
                    working = false
                    if (rendered == null) {
                        refusal = "There was not enough memory on this device to build the plate. " +
                            "Close the other photographs on this stage and try again."
                        return@launch
                    }
                    plateBitmap = rendered
                    plate = rendered.asImageBitmap()
                    plateNote = "${outcome.plane.width}×${outcome.plane.height} pixels, " +
                        "made in ${outcome.elapsedMs} ms on this device."
                }
            }
        }
    }

    fun attach() {
        val rendered = plateBitmap ?: return
        working = true
        scope.launch {
            // The staging file goes under filesDir, never cacheDir — see [DwMediaBridge.newCaptureFile]
            // — and its name says what it is, because months later somebody reading the workshop's
            // media directory has only the filenames. It does NOT carry the photograph's own stem the
            // way the web's `derivedPlateName` does: a handset capture is already named
            // `capture-<uuid>.jpg`, so there is no stem to carry, and the honest link between the two
            // is the registry pairing (`image` and this field, on one record) rather than the name.
            val file = media.newCaptureFile(if (lineArt) "-lineart.png" else "-straightened.png")
            // `Dispatchers.IO` and not `Default`: this is a PNG encode followed by a write and an
            // fsync to the flash, which blocks rather than computes. Occupying a `Default` thread —
            // there are only as many as there are cores — while the disk is the thing being waited on
            // is how the decode of the NEXT photograph ends up queued behind a file write.
            val ok = withContext(Dispatchers.IO) { DwSketchPlate.platePng(rendered, file) }
            working = false
            if (!ok) {
                runCatching { file.delete() }
                onError("The straightened image could not be written to this device.")
                return@launch
            }
            // Not gated on the import completing, exactly as [DwMediaCaptureCard]'s `adopt` is not:
            // the bridge launches its own coroutine, reports its own failures, and calls back only on
            // success. A second flag waiting on a callback that a failed import never makes would
            // leave this button disabled for the rest of the session.
            media.attach(listOf(dwCaptureUri(context, file)), field) { ids ->
                val id = ids.firstOrNull()
                if (id != null) {
                    onAttached(id)
                    onMessage("The plate is attached as ${field.label}. The photograph is unchanged.")
                }
                // Only after the callback: the import made its own durable copy, so deleting earlier
                // would be deleting the only copy if the import had failed.
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Straighten a photographed sketch",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Close", fontSize = 12.sp)
            }
        }

        if (sources.size > 1) {
            DwPanelLabel("Which photograph")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { candidate ->
                    DwPanelChip(
                        // The image field's own label is on the chip, because two photographs of one
                        // record often differ only in which slot they were attached to.
                        label = "${candidate.fieldLabel} · ${candidate.item.displayName}",
                        selected = candidate.item.id == source.item.id,
                        enabled = enabled,
                        onClick = { sourceId = candidate.item.id },
                    )
                }
            }
        }

        // "In any order" is said out loud because it is the one thing about this control a designer
        // cannot find out by trying: placing the four in reading order describes a crossed
        // quadrilateral, and [DwSketchRectify.orderCorners] silently untwists it when the plate is
        // made. Somebody who does not know that hesitates over which handle is which — and the
        // handles cannot answer, because they are never re-ordered. See CORNER_NAME.
        DwPanelLabel("Corners — tap the photograph to place the selected handle, or drag one. In any order.")
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

        /* ── The photograph ─────────────────────────────────────────────────────────────────── */

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
                        "The photograph of the sheet. Tap it to place the corner selected above; " +
                            "drag a handle to move it; pinch to zoom."
                }
                // `pan` and `active` are deliberately NOT keys: both are read live through their state
                // delegates when the tap arrives, so listing them would tear this detector down and
                // rebuild it on every frame of a pan for no benefit at all.
                .pointerInput(enabled, displayScale) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        if (displayScale <= 0f) return@detectTapGestures
                        setCorner(active, viewToImage(offset))
                        // Walk to the next corner but STOP at 4 rather than wrapping to 1: wrapping
                        // would make a fifth tap silently move the corner the designer placed first.
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
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )

                bitmap == null -> Text(
                    "This photograph could not be opened on this device. Nothing has happened to it — " +
                        "a line-art file can still be attached with the file picker above.",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )

                else -> {
                    val markColor = MaterialTheme.colorScheme.primary
                    val discColor = MaterialTheme.field.surface50
                    /*
                     * The image and the quadrilateral are drawn by ONE Canvas through ONE transform.
                     * Two draw paths over the same photograph is how the handles and the picture come
                     * to disagree by a few pixels after a pinch — invisible in review, and here it
                     * would be a plate cropped a few pixels inside the paper's edge.
                     */
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
                    }

                    (0 until 4).forEach { index ->
                        val corner = corners[index] ?: return@forEach
                        DwMarkHandle(
                            key = index,
                            badge = CORNER_BADGE[index],
                            name = CORNER_NAME[index],
                            // The seeds ARE a usable quadrilateral here — see the file header for why
                            // this panel does not gate on every handle having been moved — so nothing
                            // is ever announced as "not placed yet".
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
            DwSketchNudgePad(
                badge = CORNER_BADGE[active],
                enabled = enabled,
                displayScale = displayScale,
            ) { delta ->
                val current = corners[active] ?: return@DwSketchNudgePad
                setCorner(active, DwPoint(current.x + delta.x, current.y + delta.y))
            }
        }

        /* ── What shape the sheet really is, and what to do with the tones ──────────────────── */

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
            "Four marked points do not say what proportion the sheet really is — recovering that needs " +
                "the camera's focal length, which these photographs do not carry. “As marked” takes each " +
                "pair of opposite edges at the length they appear, which is the honest estimate. If you " +
                "know the sheet was A4, saying so here is knowledge the pixels do not have.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
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
            "A pencil sketch with soft shading, or a very faint construction line, is a drawing whose " +
                "information is in the greys — thresholding it to black and white throws that away. Make " +
                "it both ways and look: you are the only one who can tell.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Button(
            onClick = { build() },
            enabled = enabled && !working && sourcePlane != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (working) "Working…" else "Make the plate", fontSize = 13.sp)
        }

        refusal?.let { reason ->
            // A refusal, with its reason. NOT styled as an error, because nothing has gone wrong with
            // the designer's work — the four handles simply do not describe a sheet, and the sentence
            // says which way to move them.
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
                    fontSize = 11.sp,
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
                    Text(note, color = MaterialTheme.field.muted, fontSize = 11.sp)
                }
                Text(
                    "Look at it before you attach it. A threshold that lost the faint construction " +
                        "lines, or that filled a shaded area in solid, is obvious here and invisible " +
                        "everywhere downstream — and you are standing next to the original sheet.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Button(
                    onClick = { attach() },
                    enabled = enabled && !working,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Attach as ${field.label}", fontSize = 13.sp)
                }
                if (!currentFileName.isNullOrBlank()) {
                    Text(
                        "“$currentFileName” is attached here now. This replaces it.",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    "The photograph in ${source.fieldLabel} is not read again after this, not " +
                        "re-encoded and not replaced. The plate is a second file that cites it.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        image?.let { decoded ->
            if (decoded.bitmap.width < decoded.sourceWidth) {
                Text(
                    // Said outright, because it is why a plate made here can be smaller than one made
                    // in a browser from the same photograph. See [DwSketchPlate]'s header.
                    "Marked on a ${decoded.bitmap.width}×${decoded.bitmap.height} working copy of a " +
                        "${decoded.sourceWidth}×${decoded.sourceHeight} photograph — the full frame is too " +
                        "large to hold in memory on a field handset. The plate is made from what you can see.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/**
 * Fine placement for the corner that is selected, and the only route to a corner that does not need
 * sight and a pointer.
 *
 * THE STEP IS FOUR SCREEN PIXELS' WORTH OF IMAGE, not one image pixel — the same rule and the same
 * reason as [DwPhotoMeasurePanel]'s pad. A fixed step in image pixels is invisible when zoomed out
 * (one pixel of a 2000 px frame in a 400 px box is a quarter of a screen pixel, so the designer taps
 * twenty times and then overshoots) and coarse when zoomed in.
 *
 * A SEPARATE PAD FROM `DwNudgePad` rather than a shared one, because that pad's four labels are built
 * from [DwPhotoMeasurePanel]'s own mark vocabulary ("Nudge R1 left") and generalising it would mean
 * passing four strings in to say the same four things. The BUTTON is shared — [DwNudgeButton] — so
 * the 48dp touch target and the tint live in one place.
 */
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
        Text("Nudge corner $badge", color = MaterialTheme.field.muted, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        /*
         * THE PLAIN ARROWS AND NOT `Icons.AutoMirrored`, which is what the deprecation warning asks
         * for and is the wrong answer here. Auto-mirroring flips the GLYPH in an RTL locale so a
         * "back" chevron follows the reading direction. These four do not point along a sentence —
         * they point at the edges of a photograph, and `Modifier.offset` places the handles in
         * layout-direction-independent coordinates. Mirrored, the left arrow would be drawn pointing
         * right while still moving the corner left, on a control whose entire job is aiming. Urdu is
         * a language this programme works in.
         */
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
