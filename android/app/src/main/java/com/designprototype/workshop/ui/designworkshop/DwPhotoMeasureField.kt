package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt for why a
// bare Material `Text` here would quietly set this panel's headings in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Measure a dimension from this photograph" — the handset's surface over [DwPhotoMeasure].
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * Stage 13's Advanced tier asks for calibrated measurements, and every dimension in the registry —
 * `lengthCm`, `widthCm`, `heightCm`, `diameterCm` — is typed off a tape measure today. A wrong
 * dimension does not stay put: it is multiplied into the cost sheet, printed on the product card, and
 * read by somebody costing a production run from a document nobody can re-measure, because by then the
 * prototype is three districts away. So the measurement has to be available while the object is still
 * in the designer's hands — which in this application means a courtyard with no connection for days.
 * `frontend/lib/photoMeasure.ts` and its `PhotoMeasureField` have had this on the web since the module
 * was written; [DwPhotoMeasure] has been on the handset, tested and passing, calling nothing. This is
 * the screen that reaches it.
 *
 * Everything below runs on this device. There is no network call anywhere in this file.
 *
 * ── IT NEVER WRITES A DIMENSION BY ITSELF ─────────────────────────────────────────────────────
 *
 * Every path ends at a button the designer presses, exactly as [DwIdentityOcrControl] does and for a
 * related reason: the number is a proposal from an inference a person can check against the object in
 * their hands, and the moment it is written into `lengthCm` it loses its error bar for ever — the
 * registry has a column for the dimension and none for the doubt. So the doubt is spent HERE, on
 * screen, while somebody can still act on it, and the button says the figure it will write.
 *
 * ── THE ASSUMPTION IS ON THE SCREEN AND NOT ONLY IN A COMMENT ─────────────────────────────────
 *
 * A ratio of pixel distances is the true length only when the reference and the object lie in one flat
 * plane square to the sensor. A scale card lying on a table and a pot standing on that table are not
 * in one plane, and the pot measured that way is wrong by however much the perspective happens to be —
 * silently, plausibly, and with nothing downstream able to notice. That sentence is rendered, in the
 * warning colours, next to the answer, every time. The four-corner method below it is the way out.
 *
 * ── THE PIXELS THE MARKS LIVE IN, WHICH IS THE ONE PLACE THIS PARTS FROM THE WEB ──────────────
 *
 * The browser hands `PhotoMeasureField` the full-resolution photograph and marks are stored in its
 * natural pixels. A handset cannot: a 12 MP frame is 48 MB decoded and this app runs on 2 GB phones,
 * so [DwImageDecode.decodeForDisplay] returns a reduced working copy (see its header) and MARKS ARE
 * STORED IN THAT COPY'S PIXELS.
 *
 * That is deliberate rather than a compromise, and it is the honest arrangement. The measured VALUE is
 * unchanged either way — every method here is a ratio or a homography, and both are invariant under a
 * uniform rescale of the marks. What changes is the ERROR BAR, and it changes in the direction that is
 * true: [DwPhotoMeasure.markSigmaForDisplayScale] converts "how precisely can a fingertip be aimed on
 * this screen" into image pixels, and a designer looking at a 2000 px working copy of a 4000 px frame
 * genuinely cannot aim to one source pixel, because that detail is not on the screen to aim at.
 * Quoting the source frame's pixels here would narrow every error bar in the feature by pure
 * assertion, which is exactly what the module refuses to do. The panel says which copy it is showing.
 *
 * The same reasoning caps the zoom at [MAX_ZOOM] rather than the web's 40x: past the point where one
 * working-copy pixel covers several screen pixels, zooming shows interpolation rather than detail, and
 * an error bar that went on narrowing would be measuring the interpolation.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Which fields a measurement may be proposed into
 * ──────────────────────────────────────────────────────────────────────────── */

/** A registry field a measurement may be proposed into, with the unit the registry declared for it. */
data class DwMeasureTarget(val field: FieldDto, val unit: String)

/**
 * The numeric fields on this entity that hold a LENGTH, in the order the form already renders them.
 *
 * READ ENTIRELY OFF THE DECLARATION, never off a key. This is the Android half of the web's
 * `stageFieldRoles.measurableLengthFields`, and it asks [DwPhotoMeasure.LENGTH_UNITS] — the very map
 * the module converts through — whether a field's declared unit is a length. ONE MAP, so a unit this
 * module cannot convert can never become a destination it writes into.
 *
 * MONEY and PERCENT are excluded by the type test, and `unit="g"` / `"days"` / `"pieces"` by the unit
 * test. A photograph cannot weigh anything, and proposing a centimetre figure into a weight in grams
 * is the exact class of silent, plausible, uncorrectable error this whole feature exists to reduce.
 *
 * Thirteen fields across four entities qualify in the bundled registry — `existingProduct`, `sketch`,
 * `prototype` and `finalProduct`, which are exactly the entities that describe a physical object
 * somebody photographs. That is not a coincidence: a field with a length unit on an entity with no
 * photograph never gets the offer, because [dwOffersPhotoMeasure] also needs an image field.
 */
internal fun dwMeasurableLengthFields(siblings: Map<String, FieldDto>): List<DwMeasureTarget> =
    siblings.values.mapNotNull { field ->
        if (field.deprecated) return@mapNotNull null
        val type = DwFieldType.of(field.type)
        if (type != DwFieldType.DECIMAL && type != DwFieldType.INT) return@mapNotNull null
        // Case-folded because the registry writes "cm" and a future field might write "CM"; trimmed
        // because a stray space in a declaration should not silently remove a field from the list.
        val unit = field.unit.trim().lowercase(Locale.ROOT)
        if (!DwPhotoMeasure.LENGTH_UNITS.containsKey(unit)) return@mapNotNull null
        DwMeasureTarget(field, unit)
    }

/**
 * Should this media field offer "measure a dimension from a photograph"?
 *
 * OFFERED ON EVERY IMAGE FIELD OF A QUALIFYING ENTITY, deliberately, and this is where it parts
 * company with [isIdentityNumberField]. The card reader can tell which photograph it wants, because a
 * card looks like a card and the registry names the field accordingly. NOTHING CAN TELL WHICH
 * PHOTOGRAPH HAS THE RULER IN IT — that is a fact about what the designer chose to lay beside the
 * object thirty seconds ago — so narrowing the offer to a "likely" image field would hide the feature
 * on precisely the photograph that was taken for it. Two offers on stage 13's prototype entity
 * (`prototypePhotos` and `turntablePhotos`) is the honest cost of not guessing.
 */
internal fun dwOffersPhotoMeasure(field: FieldDto, siblings: Map<String, FieldDto>): Boolean {
    if (field.deprecated) return false
    val type = DwFieldType.of(field.type)
    if (type != DwFieldType.IMAGE && type != DwFieldType.IMAGE_LIST) return false
    return dwMeasurableLengthFields(siblings).isNotEmpty()
}

/* ────────────────────────────────────────────────────────────────────────────
 * The marks
 * ──────────────────────────────────────────────────────────────────────────── */

private enum class DwMeasureMode { SCALE, RECTIFY }

private enum class DwMarkId { REF_A, REF_B, C0, C1, C2, C3, TGT_A, TGT_B }

/** One mark: where it is, how precisely it was placed, and whether it has been placed at all. */
private data class DwMark(
    val point: DwPoint,
    /** Per-mark uncertainty in WORKING-COPY pixels, from the zoom this mark was last positioned at. */
    val sigma: Double,
    /** False while the mark is still sitting where it was seeded — see [SEEDS]. */
    val placed: Boolean,
)

private val SCALE_MARKS = listOf(DwMarkId.REF_A, DwMarkId.REF_B, DwMarkId.TGT_A, DwMarkId.TGT_B)
private val RECTIFY_MARKS =
    listOf(DwMarkId.C0, DwMarkId.C1, DwMarkId.C2, DwMarkId.C3, DwMarkId.TGT_A, DwMarkId.TGT_B)

/**
 * The badge on each handle and the sentence a screen reader gets.
 *
 * EVERY HANDLE CARRIES ITS OWN NAME, so which mark is which never depends on where it happens to be or
 * on the colour it is drawn in. Reference and corner handles are filled and object handles are
 * outlined, but that distinction is decoration on top of the label rather than the thing carrying it —
 * the panel has to work in bright sun, in greyscale, and read aloud.
 */
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
    DwMarkId.C1 to "Rectangle corner 2, along the width edge from corner 1",
    DwMarkId.C2 to "Rectangle corner 3, diagonally opposite corner 1",
    DwMarkId.C3 to "Rectangle corner 4",
    DwMarkId.TGT_A to "The dimension, first end",
    DwMarkId.TGT_B to "The dimension, second end",
)

/**
 * Where each mark starts, as a fraction of the photograph.
 *
 * SEEDED RATHER THAN EMPTY, because an empty photograph with an instruction to tap six times is a
 * state a designer can get wrong — a stray tap makes a mark nobody wanted — and cannot see the shape
 * of. Seeded marks show what is being asked for immediately. They are also flagged unplaced, and NO
 * MEASUREMENT IS SHOWN until every mark has been moved or tapped into position: a reading taken off
 * the default layout would be a confident number about nothing at all.
 */
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

/**
 * Things a designer in this programme actually has to hand, with the sizes they actually are.
 *
 * A preset removes the one step most likely to be got wrong — typing the reference length on a phone
 * keyboard in a courtyard — and the A4 sheet is here twice because it is both the commonest scale bar
 * and the commonest known rectangle. NOTHING IS PRESELECTED: a reference the designer did not choose
 * is a reference nobody checked was in the photograph.
 */
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

/** See the file header: past this the screen is showing interpolation rather than photograph. */
private const val MAX_ZOOM = 12f

/**
 * A rounded value as the web's `toFixed(decimals)` renders it.
 *
 * NO SECOND ROUNDING HAPPENS HERE, which is what keeps `%.2f` (HALF_UP on the decimal rendering) from
 * disagreeing with JavaScript's `toFixed`: [DwPhotoMeasure.roundToUncertainty] has already rounded the
 * binary value to exactly this many places through its own `Math.round` port, so the formatter has
 * nothing left to decide. Reaching for `%.2f` on a RAW measurement would reintroduce the divergence
 * that [DwPhotoMeasure]'s header is about.
 */
internal fun dwFormatRounded(rounded: DwRoundedValue): String =
    String.format(Locale.ROOT, "%.${rounded.decimals}f", rounded.value)

/* ────────────────────────────────────────────────────────────────────────────
 * The panel
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The whole surface, collapsed until the designer asks for it.
 *
 * [photos] are the IMAGE attachments already on this field — uploaded or still only on this device,
 * both equally measurable, which is what keeps the feature working on a photograph taken thirty
 * seconds ago with no signal. [targets] is what a measurement may be proposed into.
 *
 * COLLAPSED BY DEFAULT AND THE STATE LIVES INSIDE THE OPEN HALF, deliberately: closing the panel drops
 * the reference to the decoded working copy, which is the largest single allocation this feature makes.
 * Re-opening decodes again — a few hundred milliseconds, off the main thread — and that is the right
 * trade on a phone whose other job right now is the camera.
 */
@Composable
internal fun DwPhotoMeasurePanel(
    photos: List<DwMediaItem>,
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    /** Write ONE registry field. Called only from a button the designer pressed. */
    onPropose: (String, JsonElement?) -> Unit,
) {
    if (photos.isEmpty() || targets.isEmpty()) return
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
                    Icons.Filled.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Measure a dimension from a photograph",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "If a ruler, a scale card or a sheet of paper is in one of these photographs, a " +
                    "dimension can be measured off it here and proposed into " +
                    targets.joinToString(", ") { it.field.label } +
                    ". It runs on this device and needs no connection.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Measure from a photograph", fontSize = 13.sp)
            }
        }
        return
    }

    DwPhotoMeasureOpen(
        photos = photos,
        targets = targets,
        rowValues = rowValues,
        enabled = enabled,
        onClose = { open = false },
        onPropose = onPropose,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwPhotoMeasureOpen(
    photos: List<DwMediaItem>,
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    onClose: () -> Unit,
    onPropose: (String, JsonElement?) -> Unit,
) {
    var photoId by remember { mutableStateOf(photos.first().id) }
    val photo = photos.firstOrNull { it.id == photoId } ?: photos.first()
    var mode by remember { mutableStateOf(DwMeasureMode.SCALE) }
    val marks = remember { mutableStateMapOf<DwMarkId, DwMark>() }
    var active by remember { mutableStateOf(DwMarkId.REF_A) }

    var image by remember { mutableStateOf<DwDisplayImage?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    var referenceLength by remember { mutableStateOf("") }
    var referenceUnit by remember { mutableStateOf("mm") }
    var rectWidth by remember { mutableStateOf("") }
    var rectHeight by remember { mutableStateOf("") }
    var rectUnit by remember { mutableStateOf("mm") }
    var proposeError by remember { mutableStateOf<String?>(null) }

    val needed = if (mode == DwMeasureMode.SCALE) SCALE_MARKS else RECTIFY_MARKS

    /**
     * Decode the chosen photograph, off the main thread, one at a time.
     *
     * `Dispatchers.Default` and never the main thread: [DwImageDecode] measures its own decode at a few
     * hundred milliseconds and sometimes approaching a second on a loaded handset, which on the main
     * thread is an unmistakable freeze. Keyed on the photograph's id, so switching photographs releases
     * the previous working copy before the next is decoded rather than holding two.
     *
     * THE MARKS ARE CLEARED WITH IT. A mark is a position on ONE photograph, in ONE working copy's
     * pixel grid; carrying it across would put the reference somewhere nobody chose, on an image of a
     * different size.
     */
    LaunchedEffect(photo.id) {
        image = null
        marks.clear()
        active = if (mode == DwMeasureMode.SCALE) DwMarkId.REF_A else DwMarkId.C0
        decoding = true
        val decoded = withContext(Dispatchers.Default) { DwImageDecode.decodeForDisplay(photo.absolutePath) }
        decoding = false
        image = decoded
    }

    /** The bitmap wrapped once per decode rather than once per frame — the wrap is not free. */
    val bitmap: ImageBitmap? = remember(image) { image?.bitmap?.asImageBitmap() }
    val imageWidth = image?.bitmap?.width?.toFloat() ?: 0f
    val imageHeight = image?.bitmap?.height?.toFloat() ?: 0f

    /** Screen pixels per working-copy pixel at zoom 1 — "fit the whole photograph in the box". */
    val fit = if (imageWidth > 0f && imageHeight > 0f && boxSize.width > 0 && boxSize.height > 0) {
        min(boxSize.width / imageWidth, boxSize.height / imageHeight)
    } else {
        0f
    }

    /** Screen pixels per working-copy pixel right now. This is what decides how precise a mark can be. */
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

    // Re-centre whenever the photograph or the box changes. Without this the marks and the image
    // disagree the first time the panel is opened after a rotation.
    LaunchedEffect(image, boxSize) {
        zoom = 1f
        pan = clampPan(Offset.Zero, 1f)
    }

    /** Seed any mark this mode needs that does not exist yet, once the working copy's size is known. */
    LaunchedEffect(image, mode) {
        if (imageWidth <= 0f || imageHeight <= 0f) return@LaunchedEffect
        for (id in needed) {
            if (marks[id] != null) continue
            val seed = SEEDS.getValue(id)
            marks[id] = DwMark(
                point = DwPoint(seed.x * imageWidth, seed.y * imageHeight),
                // The 1:1 fallback. A seeded mark's sigma is replaced the instant it is actually
                // placed, and nothing is measured until every mark has been.
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
        // Clamped to the photograph: a mark dragged off the edge is a coordinate outside the image,
        // and a homography solved through one measures a plane that was never photographed.
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

    /** The worst of the marks this measurement rests on — an error bar is only as good as its weakest. */
    val markSigmaPx = needed.mapNotNull { marks[it]?.sigma }.maxOrNull()

    /*
     * Recomputed on every composition rather than remembered, and that is a measured choice rather
     * than laziness: the rectified path is twenty-five 8x8 Gaussian eliminations (one answer and
     * twenty-four perturbations), which is a few thousand floating-point operations. A `remember` keyed
     * on a snapshot map would have to copy the map to compare it, which is the more expensive half.
     */
    val result: DwMeasureResult? = when {
        !allPlaced || markSigmaPx == null -> null
        mode == DwMeasureMode.SCALE -> {
            val length = referenceLength.trim().replace(",", "").toDoubleOrNull()
            val refA = marks[DwMarkId.REF_A]
            val refB = marks[DwMarkId.REF_B]
            if (length == null || refA == null || refB == null) {
                null
            } else {
                DwPhotoMeasure.measureBySameScale(
                    reference = DwScaleReference(refA.point, refB.point, length, referenceUnit),
                    target = DwSegment(marks.getValue(DwMarkId.TGT_A).point, marks.getValue(DwMarkId.TGT_B).point),
                    markSigmaPx = markSigmaPx,
                )
            }
        }
        else -> {
            val width = rectWidth.trim().replace(",", "").toDoubleOrNull()
            val height = rectHeight.trim().replace(",", "").toDoubleOrNull()
            if (width == null || height == null) {
                null
            } else {
                DwPhotoMeasure.measureByRectification(
                    corners = listOf(DwMarkId.C0, DwMarkId.C1, DwMarkId.C2, DwMarkId.C3)
                        .map { marks.getValue(it).point },
                    rectangle = DwKnownRectangle(width, height, rectUnit),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Measure a dimension from a photograph",
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

        if (photos.size > 1) {
            DwMeasureLabel("Photograph")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                photos.forEach { candidate ->
                    DwMeasureChip(
                        label = candidate.displayName,
                        selected = candidate.id == photo.id,
                        enabled = enabled,
                        onClick = { photoId = candidate.id },
                    )
                }
            }
        }

        // Two real buttons rather than a dropdown, because the choice IS the explanation and a designer
        // has to read both to make it.
        DwMeasureLabel("Method")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                DwMeasureMode.SCALE to "Same plane (2 marks)",
                DwMeasureMode.RECTIFY to "Tilted — rectify a rectangle (4 corners)",
            ).forEach { (value, label) ->
                DwMeasureChip(
                    label = label,
                    selected = mode == value,
                    enabled = enabled,
                    onClick = {
                        mode = value
                        active = if (value == DwMeasureMode.SCALE) DwMarkId.REF_A else DwMarkId.C0
                        proposeError = null
                    },
                )
            }
        }

        // THE ASSUMPTION, ON SCREEN. See the file header for why this is not a comment.
        if (mode == DwMeasureMode.SCALE) {
            DwMeasureNote(
                warning = true,
                text = "This is only true when the reference and the thing you are measuring lie in the " +
                    "same flat plane, square to the camera. A scale card lying on a table and a pot " +
                    "standing on that table are not in one plane, and a dimension measured across a " +
                    "tilted object comes out wrong — by however much the angle happens to be, with " +
                    "nothing later able to tell. If the photograph is at an angle, use the four-corner " +
                    "method instead.",
            )
        } else {
            DwMeasureNote(
                warning = false,
                text = "Mark the four corners of the rectangle in order around it — 1, 2, 3, 4 walking " +
                    "round the edge, not in reading order. The tilt of that surface is then corrected " +
                    "for exactly. The object still has to be lying on that surface: what is corrected " +
                    "is the angle of the plane, not the height of something standing up off it.",
            )
        }

        DwMeasureLabel("Marks — tap the photograph to place, drag a handle or nudge to adjust")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            needed.forEach { id ->
                DwMeasureChip(
                    label = "${MARK_BADGE.getValue(id)} · ${if (marks[id]?.placed == true) "placed" else "not placed"}",
                    selected = active == id,
                    enabled = enabled,
                    onClick = { active = id },
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
                        "The photograph being measured. Tap it to place the mark selected above; " +
                            "drag a handle to move it; pinch to zoom."
                }
                // Tap places the mark the designer is currently working on and moves the cursor to the
                // next — the four-corner case is six marks, and reaching for a chip between each would
                // make it eleven actions instead of six. The zoom buttons and the mark handles are
                // CHILDREN of this box, and a child consumes the event before this detector sees it,
                // so neither of them can place a mark by accident.
                // `pan` and `active` are deliberately NOT keys: both are read live through their
                // state delegates when the tap arrives, so listing them would tear this detector down
                // and rebuild it on every frame of a pan for no benefit at all.
                .pointerInput(enabled, displayScale, needed) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        if (displayScale <= 0f) return@detectTapGestures
                        setMark(active, viewToImage(offset))
                        val index = needed.indexOf(active)
                        if (index in 0 until needed.size - 1) active = needed[index + 1]
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
                    // Silent failure would leave an empty grey box that reads as a broken app. This
                    // device could not decode these bytes; the photograph itself is untouched.
                    "This photograph could not be opened on this device. Nothing has happened to it — " +
                        "the dimension can still be typed in.",
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
                     * The image and the guide lines are drawn by ONE Canvas through ONE transform.
                     * Two draw paths over the same photograph is how the marks and the picture come to
                     * disagree by a few pixels after a pinch — invisible in review and worth a
                     * percent on a measurement.
                     */
                    Canvas(modifier = Modifier.matchParentSize()) {
                        // Before the box has been measured there is no scale to draw at, and a
                        // zero-sized destination rect is not something to hand a Canvas.
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
                        if (mode == DwMeasureMode.SCALE) {
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
                            id = id,
                            mark = mark,
                            enabled = enabled,
                            isActive = active == id,
                            markColor = markColor,
                            discColor = discColor,
                            positionInView = { imageToView(marks[id]?.point ?: mark.point) },
                            onSelect = { active = id },
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

        /*
         * The zoom readout is part of the measurement, not chrome — see the file header. A polite live
         * region rather than a bare line, so the narrowing error bar reaches a designer who cannot see
         * the marks move.
         */
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
                    append(" pixels of the working copy, which is what the error bar below is built from. ")
                    append("Zooming in genuinely narrows it.")
                } else {
                    append("Opening the photograph…")
                }
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        image?.let { decoded ->
            val shownWidth = decoded.bitmap.width
            if (shownWidth < decoded.sourceWidth) {
                Text(
                    // Said outright, because it is why this handset's error bar is wider than the
                    // browser's for the same photograph and the same marks. See the file header.
                    "Marked on a ${shownWidth}×${decoded.bitmap.height} working copy of a " +
                        "${decoded.sourceWidth}×${decoded.sourceHeight} photograph — the full frame is too " +
                        "large to hold in memory on a field handset, so the error bar is worked out in the " +
                        "pixels you can actually see.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        if (bitmap != null) DwNudgePad(active = active, enabled = enabled, displayScale = displayScale) { delta ->
            val current = marks[active]?.point ?: return@DwNudgePad
            setMark(active, DwPoint(current.x + delta.x, current.y + delta.y))
        }

        /* ── The known size ─────────────────────────────────────────────────────────────────── */

        if (mode == DwMeasureMode.SCALE) {
            DwMeasureLabel("How long is the reference, really?")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SCALE_PRESETS.forEach { preset ->
                    DwMeasureChip(
                        label = preset.label,
                        selected = false,
                        enabled = enabled,
                        onClick = {
                            referenceLength = dwTrimNumber(preset.length)
                            referenceUnit = preset.unit
                        },
                    )
                }
            }
            OutlinedTextField(
                value = referenceLength,
                onValueChange = { referenceLength = it },
                label = { Text("Reference length") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DwUnitChips(selected = referenceUnit, enabled = enabled) { referenceUnit = it }
        } else {
            DwMeasureLabel("How big is the rectangle, really?")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RECT_PRESETS.forEach { preset ->
                    DwMeasureChip(
                        label = preset.label,
                        selected = false,
                        enabled = enabled,
                        onClick = {
                            rectWidth = dwTrimNumber(preset.width)
                            rectHeight = dwTrimNumber(preset.height)
                            rectUnit = preset.unit
                        },
                    )
                }
            }
            OutlinedTextField(
                value = rectWidth,
                onValueChange = { rectWidth = it },
                label = { Text("Width, corner 1 to corner 2") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rectHeight,
                onValueChange = { rectHeight = it },
                label = { Text("Height, corner 2 to corner 3") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DwUnitChips(selected = rectUnit, enabled = enabled) { rectUnit = it }
            Text(
                "The width is the edge from corner 1 to corner 2; the height is from corner 2 to " +
                    "corner 3. Getting the two the wrong way round measures a real rectangle that is " +
                    "not the one in the photograph.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        DwMeasurementReadout(
            result = result,
            allPlaced = allPlaced,
            needed = needed,
            marks = marks,
            mode = mode,
            targets = targets,
            rowValues = rowValues,
            enabled = enabled,
            proposeError = proposeError,
            onPropose = { target, text ->
                // ONE coercion, the same one a typed answer goes through, so a measurement cannot
                // enter the draft in a shape typing could not have produced — and a field with a
                // declared maximum refuses a 900 cm reading here rather than failing the save later
                // and blaming the network.
                val coerced = DwValues.coerce(target.field, text)
                if (coerced.error != null) {
                    proposeError = coerced.error
                } else {
                    proposeError = null
                    onPropose(target.field.key, coerced.value)
                }
            },
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The answer, its error bar, and the only place anything is written
 * ──────────────────────────────────────────────────────────────────────────── */

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
            "${remaining.size} of ${needed.size} marks still to place " +
                "(${remaining.joinToString(", ") { MARK_BADGE.getValue(it) }}). Nothing is measured " +
                "until every mark is where it belongs — a reading off the marks as they were laid out " +
                "would be a confident number about nothing.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        return
    }

    if (result == null) {
        Text(
            if (mode == DwMeasureMode.SCALE) {
                "Type how long the reference really is, and the measurement appears here."
            } else {
                "Type how big the rectangle really is, and the measurement appears here."
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
        return
    }

    if (result is DwMeasureResult.Refusal) {
        // A refusal, with its reason. It is NOT styled as an error, because nothing has gone wrong
        // with the designer's work — the marks simply do not support a number, and the sentence says
        // which. See [DwPhotoMeasure]'s header for why a refusal beats a number with no error bar.
        DwMeasureNote(warning = true, text = result.reason, polite = true)
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
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${dwFormatRounded(shown)} ± ${dwFormatRounded(doubt)} ${measurement.unit}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "That is ±${String.format(Locale.ROOT, "%.1f", measurement.relativeUncertainty * 100)}%, from a " +
                "reference ${measurement.referencePixels.roundToInt()} pixels long and an object " +
                "${measurement.targetPixels.roundToInt()} pixels long in this working copy. The error bar is " +
                "how far the answer moves when each mark is nudged by the amount a mark can be placed to at " +
                "this zoom — it is not a guess about the camera, and it does not include the reference being " +
                "the wrong length.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        measurement.tiltCorrection?.let { tilt ->
            Text(
                buildString {
                    append("Correcting for the tilt of that surface changed this by ")
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
                            "That is small enough that the two-mark method would have done here."
                        } else {
                            "That is why the four corners were worth marking."
                        }
                    )
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        DwMeasureLabel("Propose this into")
        targets.forEach { target ->
            val converted = DwPhotoMeasure.convertLength(measurement.value, measurement.unit, target.unit)
            val convertedDoubt =
                DwPhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, target.unit)
            if (converted == null || convertedDoubt == null) {
                // A unit this module cannot convert must not become a destination. Said out loud
                // rather than silently omitted, so nobody wonders where the button went.
                Text(
                    "${target.field.label} is measured in ${target.unit}, which this cannot convert to.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                )
                return@forEach
            }
            val proposal = DwPhotoMeasure.roundToUncertainty(converted, convertedDoubt)
            val text = dwFormatRounded(proposal)
            val current = DwValues.text(rowValues[target.field.key])
            Button(
                onClick = { onPropose(target, text) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${target.field.label}: $text ${target.unit}", fontSize = 13.sp)
            }
            if (current.isNotBlank()) {
                Text(
                    "Currently “$current”. This replaces it.",
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 11.sp,
                )
            }
        }

        proposeError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        Text(
            "The figure is rounded to the precision its own error bar reaches, because once it is in " +
                "the field the error bar is gone — the number of digits is the only thing left saying " +
                "how well it was measured.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Pieces
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One draggable mark.
 *
 * THE TOUCH TARGET IS 48dp AND THE DISC IS 26dp, which is not a contradiction. This app applies a 48dp
 * floor wherever a control was thought about (see ISLAND_TOUCH_TARGET in ui/AppNavigation.kt), and a
 * mark that has to be aimed at is the last place to make an exception. But a 48dp disc drawn on a
 * photograph would hide the very corner it is marking, and six of them would overlap. So the disc is
 * small enough to see past and the transparent box around it is large enough to grab.
 */
@Composable
private fun DwMarkHandle(
    id: DwMarkId,
    mark: DwMark,
    enabled: Boolean,
    isActive: Boolean,
    markColor: Color,
    discColor: Color,
    positionInView: () -> Offset,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    val isTarget = id == DwMarkId.TGT_A || id == DwMarkId.TGT_B
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Read in the LAYOUT phase, so a pan or a pinch moves every handle without recomposing
            // the panel around it.
            .offset {
                val view = positionInView()
                IntOffset((view.x - 24.dp.toPx()).roundToInt(), (view.y - 24.dp.toPx()).roundToInt())
            }
            .size(48.dp)
            .semantics {
                contentDescription = MARK_NAME.getValue(id) +
                    (if (mark.placed) "" else ", not placed yet") +
                    ". Drag it, or select it and use the nudge arrows below the photograph."
            }
            // Selecting a handle consumes the tap, so it cannot also fall through to the photograph
            // and place whichever OTHER mark happened to be active.
            .pointerInput(id, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSelect() }
            }
            .pointerInput(id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, amount ->
                        // Consumed so the viewport beneath does not read the same movement as a pan
                        // and slide the photograph out from under the mark being dragged.
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
                .background(if (isTarget) discColor else markColor, CircleShape)
                .border(
                    BorderStroke(if (isActive) 3.dp else 2.dp, if (isTarget) markColor else discColor),
                    CircleShape,
                ),
        ) {
            Text(
                MARK_BADGE.getValue(id),
                color = if (isTarget) markColor else discColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Fine placement for the mark that is selected, and the only route to a mark that does not need sight
 * and a pointer.
 *
 * THE STEP IS FOUR SCREEN PIXELS' WORTH OF IMAGE, not one image pixel. A fixed step in image pixels is
 * invisible when zoomed out (one pixel of a 2000 px frame in a 400 px box is a quarter of a screen
 * pixel — the mark does not appear to move, so the designer taps twenty times and then overshoots) and
 * coarse when zoomed in. Scaling it with the zoom means a nudge always moves the mark by the same
 * visible amount, and zooming in is what makes it fine — the same relationship the error bar has.
 */
/*
 * THE PLAIN ARROWS AND NOT `Icons.AutoMirrored`, which is what the deprecation warning asks for and
 * is the wrong answer here. Auto-mirroring exists so a "back" or "next" chevron follows the reading
 * direction, and it flips the GLYPH in an RTL locale. These four do not point along a sentence — they
 * point at the edges of a photograph, and `Modifier.offset` places the marks in layout-direction-
 * independent coordinates. Mirrored, the left arrow would be drawn pointing right while still moving
 * the mark left, on a control whose entire job is aiming. Urdu is a language this programme works in.
 */
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
            fontSize = 11.sp,
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
private fun DwNudgeButton(
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
private fun DwZoomButton(
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
private fun DwMeasureLabel(text: String) {
    Text(text, color = MaterialTheme.field.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/**
 * A chip, drawn selected or not.
 *
 * `selected` is carried by the FILL and by the word inside it — a preset chip is never selected, and a
 * mark chip says "placed" or "not placed" in words next to its badge, so nothing here depends on
 * telling two purples apart in direct sunlight.
 */
@Composable
private fun DwMeasureChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
            Text(label, fontSize = 12.sp)
        }
    }
}

/** The assumption card, and the refusal card. Same shape; the colour says how loud it is. */
@Composable
private fun DwMeasureNote(warning: Boolean, text: String, polite: Boolean = false) {
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
            // Decorative. The sentence carries the meaning, so it survives greyscale, colour-blindness
            // and a screen reader that never sees the icon.
            contentDescription = null,
            tint = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.body,
            fontSize = 11.sp,
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
        // Straight off the module's own map, so a unit that can be chosen here is by construction a
        // unit it can convert. A hard-coded list would be a second opinion about what a length is.
        DwPhotoMeasure.LENGTH_UNITS.keys.forEach { unit ->
            DwMeasureChip(
                label = unit,
                selected = unit == selected,
                enabled = enabled,
                onClick = { onSelect(unit) },
            )
        }
    }
}

/** "210" rather than "210.0", and "85.6" still "85.6" — a preset should read like a printed size. */
private fun dwTrimNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.ROOT, "%s", value)
