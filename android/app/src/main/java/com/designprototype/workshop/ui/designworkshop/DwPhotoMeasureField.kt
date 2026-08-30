package com.designprototype.workshop.ui.designworkshop

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
 * Every path ends at a button the designer presses, exactly as [DwIdentityCardControl] does and for a
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
 * How tall the photograph's viewport is allowed to get, as width ÷ height.
 *
 * NEVER TALLER THAN IT IS WIDE ([MIN_VIEWPORT_RATIO] = 1), because a portrait frame drawn at its true
 * shape is about 450dp of card on the handset this feature exists for, and the report this pair of
 * constants answers was about a card too tall to get past. NEVER FLATTER THAN 2:1
 * ([MAX_VIEWPORT_RATIO]), because a panorama drawn at its true shape leaves a strip too shallow to aim
 * a mark in, and aiming is the whole job. Between those two the viewport is EXACTLY the shape of the
 * working copy, so there is no letterbox on either edge; outside them the letterbox comes back, which
 * is the smaller of the two costs and is why the band is as wide as it is.
 */
private const val MIN_VIEWPORT_RATIO = 1f
private const val MAX_VIEWPORT_RATIO = 2f

/**
 * The floor under the viewport while there is NOTHING in it — decoding, or a photograph this device
 * could not open. It is the two zoom buttons' own 44dp plus their 6dp padding and a little air, and
 * nothing more: the box wraps whichever sentence is in it rather than reserving room for a picture
 * that is not there.
 */
private val EMPTY_VIEWPORT_MIN_HEIGHT = 96.dp

/** The mark the cursor sits on when a method is chosen — the first one that method asks for. */
private fun dwFirstMark(mode: DwMeasureMode): DwMarkId =
    if (mode == DwMeasureMode.SCALE) DwMarkId.REF_A else DwMarkId.C0

/**
 * THE ONE WORD EVERY COLLAPSE CONTROL ON EVERY DERIVATION CARD SAYS.
 *
 * Internal so `DwPhotoMeasureFieldTest` can pin that the header's door and the foot's door are
 * labelled the same thing — two doors out of one card that disagreed about what they were would read
 * as two different actions.
 *
 * ── IT IS THREE CARDS' WORD NOW, NOT ONE CARD'S, AND THAT IS THE POINT ────────────────────────
 *
 * The measuring card, the straightening panel and the tracing panel all sit on one record, one under
 * the other, and until this constant was shared they said "Close" from three separate literals with
 * three different icon sizes (16 dp here, 14 dp there). Nothing was WRONG on any one screen; what was
 * wrong was that three cards doing one thing had three descriptions of it, which is the class of
 * drift requirement 6 is about. See [DwPanelCollapseButton].
 */
internal const val DW_PANEL_COLLAPSE_WORD = "Close"

/** What TalkBack is told the press will DO, which a chevron beside the words cannot say. */
internal const val DW_MEASURE_COLLAPSE_ACTION = "Collapse the measuring card"

/** The other direction of the same sentence. See [DW_MEASURE_COLLAPSE_ACTION]. */
internal const val DW_MEASURE_EXPAND_ACTION = "Expand the measuring card"

/**
 * The card's name, said once, in both states. See [DwPanelDisclosureHeader.title].
 */
internal const val DW_MEASURE_CARD_TITLE = "Measure a dimension from a photograph"

/**
 * **WHERE "THESE PHOTOGRAPHS" CAME FROM, ON THE ONE SURFACE WHERE MORE THAN ONE FIELD FEEDS THIS
 * CARD — requirement 7 made visible.**
 *
 * ── WHAT IT IS FOR ────────────────────────────────────────────────────────────────────────────
 *
 * `DwSketchDerivationSection` hands one measuring card the photographs of EVERY image field the
 * entity declares. A prototype declares two — `prototypePhotos` ("Prototype photographs") and
 * `turntablePhotos` ("360° capture") — and `dwOffersPhotoMeasure` answers true for both, so the stage
 * form mounts TWO of these cards on one prototype, each able to see only its own field. A designer
 * who shot the frame with the ruler in it into the turn and the clean frames into the photographs had
 * the picture they needed on one card and the dimension they wanted on the other. Merging the lists
 * is the fix; this sentence is the only thing that makes the fix legible, because a merged list of
 * file names is indistinguishable from an unmerged one at a glance.
 *
 * ── WHY IT IS SILENT ON ONE FIELD, WHICH IS A JUDGEMENT AND NOT A SHORTCUT ────────────────────
 *
 * **One field is not a merge, and this sentence is about a merge.** On a sketch the list is `image`
 * alone; on the stage form's mounts the card sits directly underneath the very field it is reading,
 * with that field's own capture card beside it. Naming the field there tells a designer where they
 * already are, which is the "printing a fact nobody needed" end of the same failure as printing one
 * nobody looked up. `RecordMeasureField`'s mount cannot even do it — the photographs there are
 * captured `Uri`s belonging to no registry field — and passes nothing, which the default handles.
 *
 * ── AND "AND", NOT "OR", WHICH IS THE OPPOSITE OF THE OTHER CLIENT'S JOINER AND DELIBERATE ────
 *
 * `MeasureFromPhotoCard.tsx`'s `fieldsPhrase` joins with " or " because it is used in a sentence
 * about where a photograph SHOULD BE ATTACHED — one destination, chosen from several. This one is
 * used in a sentence about where the photographs on screen ALREADY ARE, which is all of those fields
 * at once. Copying the joiner would have carried the punctuation and dropped the meaning: "or" would
 * tell a designer the card is reading one of the two and leave them guessing which.
 *
 * Pure and taking strings, so `DwPhotoMeasureFieldTest` can pin the wording with no composition.
 */
internal fun dwMeasureSpansFieldsClause(photoFieldLabels: List<String>): String {
    val named = photoFieldLabels.map { it.trim() }.filter { it.isNotEmpty() }
    if (named.size < 2) return ""
    return " These are every photograph on the record — " +
        named.joinToString(" and ") { "“$it”" } +
        " together — so the frame with the ruler in it counts wherever it was attached."
}

/**
 * What a derivation card is about to write OVER, and therefore what kind of thing is already there.
 *
 * Two values because there are two, and both are real. A plate and a line-art drawing are FILES on
 * the record; a measured dimension is a NUMBER in a registry column. "Attached" is true of the first
 * and false of the second — a designer told that "24.0" is *attached* to `lengthCm` would be looking
 * for a paperclip in a form that has never had one — so the noun changes and nothing else does.
 */
internal enum class DwPanelHolds {
    /** A file in a FILE field — what [DwSketchTracePanel] and [DwSketchRectifyPanel] attach. */
    FILE,

    /** A value in a numeric field — what [DwPhotoMeasurePanel] proposes. */
    VALUE,
}

/**
 * **"THERE IS SOMETHING HERE ALREADY, AND THIS BUTTON REPLACES IT" — said the same way by all three
 * derivation cards.**
 *
 * ── THE DRIFT THIS ENDS ───────────────────────────────────────────────────────────────────────
 *
 * One fact, three literals, until 2026-08-29 — and the three cards sit one under the other on one
 * record, so a designer met all three in a single scroll:
 *
 *  * the tracing panel: *"“sheet-3.svg” is attached here now. Attaching replaces it."*
 *  * the straightening panel, writing into **the same field, from the same button**:
 *    *"“sheet-3.svg” is attached here now. This replaces it."*
 *  * the measuring card: *"Currently “24.0”. This replaces it."*
 *
 * The first two differ in a verb while describing one act on one field, which is drift with no
 * argument behind it at all. The third additionally reverses the sentence — the thing that is there
 * comes second rather than first — so the one clause a designer is scanning for ("what am I about to
 * lose") sits in a different place on a card eighty pixels further down.
 *
 * **THE WARNING BEFORE A DESTRUCTIVE WRITE IS THE LAST PLACE TO BE INVENTIVE.** A designer reading
 * down this record is deciding whether to overwrite something they cannot get back — a single-valued
 * FILE field replaces its value and the old media id is gone from the row, and a registry column has
 * no history — and a sentence they have already read once is a sentence they can check at a glance
 * the second time. This is the same argument [DW_PANEL_COLLAPSE_WORD] makes about the word "Close",
 * and it matters more here, because getting a close wrong costs a scroll and getting this wrong
 * costs the file.
 *
 * ── NULL RATHER THAN AN EMPTY STRING, AS THE TWO SUMMARIES DO ─────────────────────────────────
 *
 * Nothing there is not a quieter warning, it is the absence of one — the same contract
 * [dwMeasureSummary] and [dwTraceCardSummary] hold, so a caller cannot accidentally render an empty
 * warning box. Blank-checked rather than only null-checked because `DwValues.text` answers "" for a
 * missing key and a `currentFileName` can arrive as whitespace from a resolver.
 *
 * Pure, so `DwPhotoMeasureFieldTest` can pin all three cards' wording on the JVM with no composition
 * to run it in.
 */
internal fun dwPanelReplaceWarning(current: String?, holds: DwPanelHolds): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val where = when (holds) {
        DwPanelHolds.FILE -> "is attached here now"
        DwPanelHolds.VALUE -> "is in this field now"
    }
    return "“$trimmed” $where. This replaces it."
}

/**
 * Everything a designer TYPED OR PLACED, held by the panel rather than by the open half.
 *
 * ── WHY THIS CLASS EXISTS ─────────────────────────────────────────────────────────────────────
 *
 * Until 2026-08-28 every field below was a `remember` inside [DwPhotoMeasureOpen], and the open half
 * is REMOVED FROM THE COMPOSITION the instant the card collapses. Collapsing therefore destroyed the
 * four corner marks, the reference length, the chosen photograph and the chosen method, and
 * re-expanding presented an untouched card. That made the one exit expensive enough that nobody used
 * it, so the only way past a configured card was to scroll the whole of it — which is precisely the
 * report this class answers. A collapse is only cheap if nothing is lost.
 *
 * ── WHAT IS KEPT AND WHAT IS DELIBERATELY NOT ─────────────────────────────────────────────────
 *
 * MARKS AND TEXT ARE CHEAP, SO THEY ARE KEPT. Six marks are six pairs of doubles and a flag; five
 * strings are five strings. Nothing here is measured in kilobytes, and all of it is work a person did
 * with their hands that this app has no way to redo for them.
 *
 * PIXELS ARE EXPENSIVE, SO THEY ARE NOT. The decoded working copy is NOT a field of this class and
 * must not become one. It is the largest single allocation the feature makes, and the file header
 * argues that re-decoding — a few hundred milliseconds, off the main thread — is the right trade on a
 * phone whose other job right now is the camera. `image`, `bitmap`, `zoom` and `pan` therefore stay
 * inside [DwPhotoMeasureOpen] and die with it. The split is deliberate: the next reader should be able
 * to see that what survives a collapse is a person's decisions, and what does not is a bitmap.
 *
 * ── `remember` AND NOT `rememberSaveable` ─────────────────────────────────────────────────────
 *
 * A collapse does not leave the composition — the panel that holds this stays composed while its open
 * half comes and goes — so `remember` is exactly the lifetime the requirement asks for. A saveable
 * would additionally survive process death, and it is not free: `Map<DwMarkId, DwMark>` is not
 * `Bundle`-writable, so it would need a hand-written `Saver`, and that saver would be a SECOND,
 * silently-versioned description of what a mark is, sitting beside [DwMark] with nothing keeping the
 * two in step. Rotation loses the marks today and still does; that is unchanged by this class rather
 * than caused by it. If somebody wants durability across a rotation later, the saver has to carry the
 * per-mark sigma as well as the point — a restored mark with a default sigma would quietly widen or
 * narrow the error bar this whole panel is about.
 */
@Stable
private class DwMeasureConfig(initialPhotoId: String) {

    /** Which photograph is being measured. Written by the chooser chips. */
    var photoId by mutableStateOf(initialPhotoId)

    /**
     * The photograph the [marks] were placed on, which is NOT always [photoId].
     *
     * It exists so that clearing the marks is a consequence of CHANGING THE PHOTOGRAPH and not a
     * consequence of the open half being composed. Those used to be the same event — `marks.clear()`
     * sat in the decode `LaunchedEffect` — and once the state moved up here that effect would have
     * wiped the marks on every single re-expansion, which is the bug this whole change is about.
     */
    var marksPhotoId by mutableStateOf(initialPhotoId)
        private set

    /** Which geometry the designer chose. Set through [chooseMode] so the cursor follows it. */
    var mode by mutableStateOf(DwMeasureMode.SCALE)
        private set

    /** The mark the nudge pad and the next tap are aimed at. */
    var active by mutableStateOf(dwFirstMark(DwMeasureMode.SCALE))

    val marks = mutableStateMapOf<DwMarkId, DwMark>()

    var referenceLength by mutableStateOf("")
    var referenceUnit by mutableStateOf("mm")
    var rectWidth by mutableStateOf("")
    var rectHeight by mutableStateOf("")
    var rectUnit by mutableStateOf("mm")

    /**
     * The photograph this card was pointed at INSTEAD of the shared one, or "" for "it is following".
     *
     * ── THE ESCAPE HATCH, AND WHY ITS STATE IS THE CARD'S RATHER THAN THE HOST'S ──────────────
     *
     * `MeasureFromPhotoCard.tsx:256-282` is the argument and it is exactly right: *"the sheet worth
     * TRACING is the drawing itself, flat and filling the frame, and the photograph worth MEASURING
     * is the one with a ruler or a scale card lying beside the object, which is a different
     * photograph of a different subject. Forcing them to be the same would make the tab worse at one
     * of its two jobs."*
     *
     * It lives HERE, next to the marks, because the marks are what it invalidates: pointing this card
     * somewhere else is a change of photograph in every sense that matters to a measurement, and
     * [usePhotograph] is already the one guard that tells "the photograph changed" from "the card was
     * re-composed". Held by the host instead, this would be a second description of a fact that has
     * consequences only in this class.
     *
     * "" IS THE ABSENCE OF AN OVERRIDE AND NOT A THIRD PHOTOGRAPH. While it is blank the card follows
     * whatever the shared choice is, including a change made while the card was shut.
     */
    var substituteId by mutableStateOf("")
        private set

    /**
     * Which marks the chosen method needs. ONE reading of that question, so the collapsed summary and
     * the open card can never disagree about how many marks are outstanding.
     *
     * Returns one of two shared singleton lists rather than building one, which matters here: it is a
     * `pointerInput` key in the open half, and a fresh list on each composition would tear the tap
     * detector down and rebuild it on every frame of a pan.
     */
    val needed: List<DwMarkId>
        get() = if (mode == DwMeasureMode.SCALE) SCALE_MARKS else RECTIFY_MARKS

    /** How many of them are actually placed — the number the collapsed summary quotes. */
    val placedCount: Int
        get() = needed.count { marks[it]?.placed == true }

    /**
     * Choose a geometry, moving the cursor to the first mark that geometry asks for.
     *
     * THE MARKS ARE NOT CLEARED, deliberately and exactly as before: the two methods share the
     * object's own two ends, and the seeding effect fills in whichever of the others the new method
     * needs. A designer who marked the object and then realised the surface is tilted keeps that work.
     */
    fun chooseMode(next: DwMeasureMode) {
        if (next == mode) return
        mode = next
        active = dwFirstMark(next)
    }

    /**
     * Adopt [id] as the photograph being measured, clearing the marks ONLY if they were placed on a
     * different one.
     *
     * A mark is a position on ONE photograph, in ONE working copy's pixel grid; carrying it across
     * would put the reference somewhere nobody chose, on an image of a different size. But RE-ENTERING
     * THE COMPOSITION IS NOT CHANGING THE PHOTOGRAPH, and the [marksPhotoId] comparison is the guard
     * that tells those two events apart — an expansion goes no further than the first line.
     *
     * [photoId] is written unconditionally, and not only on the clearing path, because the two part
     * company when the chosen photograph is DETACHED from the field: the panel then falls back to the
     * first one it still has, and without this the chooser chips would go on showing a selection that
     * is no longer in the list, with none of the visible chips marked.
     */
    fun usePhotograph(id: String) {
        photoId = id
        if (id == marksPhotoId) return
        marksPhotoId = id
        marks.clear()
        active = dwFirstMark(mode)
    }

    /**
     * Follow the photograph a host above chose — unless this card has been pointed somewhere else.
     *
     * CALLED FROM THE PANEL AND NOT THE OPEN HALF, so that a shared choice made while this card is
     * SHUT still lands: the marks belong to a photograph, and coming back to an expanded card holding
     * marks placed on a picture that is no longer the subject is precisely the incoherence a shared
     * owner is supposed to remove rather than introduce.
     */
    fun followShared(id: String) {
        if (substituteId.isNotBlank()) return
        usePhotograph(id)
    }

    /** Point this card at a different one of the record's photographs, and nothing else with it. */
    fun measureInstead(id: String) {
        substituteId = id
        usePhotograph(id)
    }

    /** Put this card back on the shared photograph. */
    fun backToShared(sharedId: String) {
        substituteId = ""
        usePhotograph(sharedId)
    }
}

/**
 * What the COLLAPSED card says is already set up, in one sentence, or null when nothing is.
 *
 * ── WHY THE COLLAPSED CARD HAS TO SAY THIS ────────────────────────────────────────────────────
 *
 * A collapsed card that says only its own title is indistinguishable from one nobody has touched, so
 * the only way to find out whether the four corners are still marked would be to expand it and scroll
 * — which is the exact cost the accordion exists to remove. This sentence is what makes collapsing
 * worth doing.
 *
 * ── IT NAMES THE TOTAL AND NOT ONLY THE COUNT ─────────────────────────────────────────────────
 *
 * "4 of 6 marks placed", never a bare "4 marks placed": the four-corner method needs six and the
 * same-plane method needs four, so the same figure reads as finished under one and half-done under
 * the other. The method is named for the same reason. A missing reference length is stated outright
 * rather than left out, because an omitted clause and a satisfied one look identical.
 *
 * Pure, and taking no [DwMarkId] or [DwMeasureMode] — plain counts, flags and strings — so that
 * `DwPhotoMeasureFieldTest` can pin the wording on the JVM with no composition to run it in.
 *
 * @param photographName the chosen photograph, and null where the field holds only one so there is no
 *   choice to report. Never truncated: this file's most repeated bug class is a line that quietly
 *   stops, and a summary that abbreviated the filename would be committing it in miniature.
 */
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
    // Nothing placed and nothing typed is not a configuration — the card should read as the invitation
    // it was before anybody opened it, rather than as a set-up nobody made.
    if (marksPlaced <= 0 && size == null) return null

    val parts = mutableListOf<String>()
    parts += if (marksPlaced <= 0) "no marks placed yet" else "$marksPlaced of $marksNeeded marks placed"
    parts += size ?: if (fourCorner) "no rectangle size yet" else "no reference length yet"
    parts += if (fourCorner) "four-corner method" else "same-plane method"
    if (!photographName.isNullOrBlank()) parts += "photograph “${photographName.trim()}”"
    return parts.joinToString(" · ")
}

/**
 * The whole surface, collapsed until the designer asks for it — and collapsible again afterwards.
 *
 * [photos] are the IMAGE attachments already on this field — uploaded or still only on this device,
 * both equally measurable, which is what keeps the feature working on a photograph taken thirty
 * seconds ago with no signal. [targets] is what a measurement may be proposed into.
 *
 * ── IT IS AN ACCORDION, AND IT WAS A ONE-WAY DOOR UNTIL 2026-08-28 ────────────────────────────
 *
 * The open card has always had a "Close" button, but it sat at the very top of a card that is a
 * photograph, a nudge pad, two text fields and a readout tall. A designer who had just placed four
 * marks and read the answer at the bottom had to scroll all of that back up to reach the only way out
 * — and found an untouched card waiting if they ever did it again. Three things changed together and
 * none of them works without the other two: the header row IS the control now (see [DwPanelDisclosureHeader]),
 * there is a second identical door at the FOOT where the work actually ends, and [DwMeasureConfig]
 * outlives the collapse so that using either door costs nothing.
 *
 * COLLAPSED BY DEFAULT, and the DECODED WORKING COPY still lives in the open half: closing drops the
 * largest single allocation this feature makes, and re-opening decodes again. See [DwMeasureConfig]
 * for why the marks go the other way.
 */
@Composable
internal fun DwPhotoMeasurePanel(
    photos: List<DwMediaItem>,
    /**
     * The registry labels of the image fields [photos] was drawn from, where the caller knows and
     * where knowing changes the sentence. Empty is the ordinary case and not a gap.
     *
     * ── WHAT THIS IS FOR, AND WHY IT IS THE FIELDS AND NOT THE PHOTOGRAPHS ────────────────────
     *
     * `DwSketchDerivationSection` hands ONE of these cards the photographs of EVERY image field the
     * entity declares. On a sketch that is one field and the parameter changes nothing. On a
     * prototype it is two — "Prototype photographs" and "360° capture" — and the merge is the whole
     * of what requirement 7 buys that half: the stage form mounts one of these cards per image field,
     * each blind to the other's, so a designer who shot the frame with the ruler in it into the turn
     * had the picture on one card and the dimension they wanted on another. Merged, that is fixed —
     * and INVISIBLY fixed, because a merged list of file names looks exactly like an unmerged one.
     * This is the parameter that lets the card say what it is looking at.
     *
     * ── A LIST OF FIELD LABELS AND NOT A LABEL PER PHOTOGRAPH, WHICH IS A DELIBERATE LIMIT ────
     *
     * The chooser chips below print `displayName` and nothing else, here and on the other client
     * (`MeasurablePhoto` is `{ key, name, url }` — no field on it either), so neither client can tell
     * a designer WHICH of two image fields a particular photograph came from. Two of this client's
     * three choosers can: `DwSketchRectifyPanel`'s and `DwSketchSharedPhotograph`'s both take
     * `List<DwSketchSource>`, which carries the label, and both put it on the chip. This card cannot
     * take that type — `RecordMeasureField` mounts it over a plain list of captured `Uri`s that
     * belong to no registry field at all — and a parallel id-to-label map beside `photos` would be a
     * second description of one list, which is the shape this feature keeps refusing. So the fields
     * are named ONCE, in words, which is also exactly what the other client does with its own
     * `photoFieldLabels`: a sentence, never a chip.
     */
    photoFieldLabels: List<String> = emptyList(),
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    /**
     * Where the photograph comes from — this card's own chooser, or a host above it.
     *
     * Defaulted to [DwSketchPhotographSupply.OwnChoice], which is what this card has always done and
     * is what both of today's mounts (`FieldRenderer`, `RecordMeasureField`) get without changing a
     * line. See that type for why the three states are one value, and [DwMeasureConfig.substituteId]
     * for the one thing this card keeps deciding for itself even when a host is supplying.
     */
    supply: DwSketchPhotographSupply = DwSketchPhotographSupply.OwnChoice,
    /**
     * Write ONE registry field. Called only from a button the designer pressed.
     *
     * The third argument is WHICH GEOMETRY PRODUCED THE NUMBER — `DwPhotoMeasure.METHOD_SCALE` or
     * `METHOD_RECTIFIED`, straight off the result, null if there somehow was none. Added 2026-08-27
     * for the record forms, which put it on the wire as a `measurementMethods` marker's `technique`
     * so a later reader can re-derive the reading; the stage surface has no use for it and ignores
     * it. It is the RESULT's method and not [DwMeasureMode], which names a screen rather than a
     * geometry and spells the second one `RECTIFY`.
     */
    onPropose: (String, JsonElement?, String?) -> Unit,
) {
    if (photos.isEmpty() || targets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    // Keyed on nothing, which is the point: this has to survive the open half coming and going. A
    // photograph that disappears from the field is handled where it is resolved below, by falling back
    // to the first — never by rebuilding this holder, which would throw the marks away on an attach.
    val config = remember { DwMeasureConfig(photos.first().id) }

    /*
      THE SHARED CHOICE LANDS HERE, IN THE HALF THAT IS COMPOSED IN BOTH STATES.

      Not in the open half, deliberately: a host can change the photograph while this card is shut,
      and a card that only noticed on re-expansion would come back holding marks placed on a picture
      that is no longer the subject — which is the incoherence a shared owner is supposed to remove
      rather than introduce. [DwMeasureConfig.followShared] is the guard that ignores it while this
      card has been deliberately pointed somewhere else.
    */
    val sharedId = (supply as? DwSketchPhotographSupply.Hosted)?.source?.item?.id
    LaunchedEffect(sharedId) {
        if (sharedId != null) config.followShared(sharedId)
    }

    /*
      AN OVERRIDE MAY NOT OUTLIVE THE PHOTOGRAPH IT POINTS AT — the same discipline
      [DwMeasureConfig.usePhotograph] already documents for `photoId`, owed to the field beside it.

      A PHOTOGRAPH CAN BE DETACHED WHILE THESE CARDS ARE ON SCREEN. On the Upload tab the capture
      cards sit directly above this section and detach through the same bridge that attached, so
      "measure a different photograph, then remove that photograph" is two presses on one screen.
      `photoId` survives that honestly — it is resolved against the list on every read and falls back
      to the first — and `substituteId` was not resolved anywhere, so it kept the dead id and the card
      went on believing an override was in force.

      WHAT THAT LOOKED LIKE, WHICH IS WORSE THAN UNTIDY. `inForce` stayed true over a card that had
      already fallen back to the shared photograph, so the collapsed half printed "Measuring a
      different photograph — “”" — an empty pair of quotes where the file name goes, because there is
      no such photograph to name — and the open half printed the SHARED photograph's name under a
      heading that says this card is measuring something the tracing panel is not. A card whose one
      job is to stop two panels quietly working from two pictures cannot be the thing that says they
      are when they are not.

      SO THE DEAD OVERRIDE IS DROPPED AND THE CARD GOES BACK TO FOLLOWING, which is where it would
      have been had the substitute never been chosen. `backToShared` re-adopts the shared photograph
      through `usePhotograph`, so the marks — which were placed on the photograph that has just gone —
      are cleared by the same guard that clears them for any other change of subject.

      KEYED ON THE FACT AND NOT ON `photos`: that list is rebuilt on every composition by
      `dwChooserDerivationSources`, and a `LaunchedEffect` keyed on it would restart far more often
      than the thing it is watching actually changes.
    */
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
        // Only where there is a choice to report. One photograph is not a decision anybody made.
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
            // The gate the "Measure from a photograph" button has always had. A read-only field must
            // not be openable, because opening it decodes a photograph.
            toggleEnabled = enabled,
            expandAction = DW_MEASURE_EXPAND_ACTION,
            collapseAction = DW_MEASURE_COLLAPSE_ACTION,
            onToggle = { open = true },
        )

        if (sharedId != null && config.substituteId.isNotBlank() && config.substituteId != sharedId) {
            // AN OVERRIDE IN FORCE IS NEVER FOLDED AWAY, and a collapse is the deepest fold there is.
            // The other client's rule ("a control that is doing something must be visible") reaches
            // further on a handset, where this card can be shut and a screen away from the shared
            // preview it is disagreeing with. See [DwMeasureDifferentPhotograph].
            //
            // "THE TRACING PANEL" IS NOT AN ASSUMPTION HERE, IT IS AN INVARIANT, and this is where a
            // reader should be able to check it. `sharedId` is non-null only under
            // `DwSketchPhotographSupply.Hosted`; the only thing in the app that passes `Hosted` is
            // `DwSketchDerivationSection`; and it passes it only where `dwSharesOnePhotograph` is
            // true, which on a record that has a measuring card requires a plate field — and a plate
            // field is what mounts the tracing panel. So a screen showing this sentence has a tracing
            // panel above it. On a prototype, which has no plate field, this card is handed
            // `OwnChoice`, `sharedId` is null, and neither this line nor the escape hatch below it
            // is composed at all. See [dwDerivationCardCount] for the arithmetic.
            Text(
                "$DW_MEASURE_ELSEWHERE_TITLE — “${chosen?.displayName.orEmpty()}”. The tracing panel " +
                    "is still working from the photograph chosen above.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        if (summary == null) {
            Text(
                "If a ruler, a scale card or a sheet of paper is in one of these photographs, a " +
                    "dimension can be measured off it here and proposed into " +
                    targets.joinToString(", ") { it.field.label } +
                    ". It runs on this device and needs no connection." +
                    // SAID HERE AND NOT OVER THE CHIPS, because this is the sentence a designer reads
                    // before they open anything, and "these photographs" is the phrase the clause
                    // qualifies. Over the chooser it would be a paragraph between a designer and the
                    // press they had already decided to make.
                    dwMeasureSpansFieldsClause(photoFieldLabels),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            // THE STATE, IN WORDS, WITHOUT EXPANDING ANYTHING — which is the half of the report that
            // is about scrolling. Drawn in the foreground colour because it is the thing the designer
            // came back to read, not chrome around it, and it says what is configured in words rather
            // than by any tint.
            Text(
                summary,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Text(
                "All of that is kept while this is closed. Opening it again comes back to the same " +
                    "marks — only the photograph itself is decoded a second time, which takes a moment.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        // KEPT RATHER THAN REPLACED BY THE HEADER. The header row is the accordion control now, but a
        // row that happens to be tappable is not a button anybody can SEE; this is the one that reads
        // as a door, and removing it would trade one discoverable way in for none.
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (summary == null) "Measure from a photograph" else "Open the measuring card again",
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * The title row of a derivation card, which IS the accordion control — the same one in both states,
 * on all three cards.
 *
 * ── ONE COMPOSABLE FOR BOTH STATES, AND SINCE 2026-08-29 FOR ALL THREE CARDS ──────────────────
 *
 * Drawn by the collapsed card and by the open one, so the two cannot drift into disagreeing about
 * where the title sits, which way the chevron points, or what a press announces. A second copy of this
 * would be a second place for the 48dp floor and the state description to go stale in.
 *
 * **AND THERE WERE TWO SUCH PLACES UNTIL THIS BECAME SHARED.** The measuring card's heading was a
 * control; the tracing panel's and the straightening panel's were inert rows — an icon and some text,
 * no chevron, no `stateDescription`, no `Role.Button`, nothing that answered a press. Three cards
 * that sit one under the other on one record therefore behaved three different ways when a designer
 * tapped their titles, and two of the three told a screen reader nothing at all about whether they
 * were open. Only the words and the icon differ now; the CONTRACT does not.
 *
 * ── `clickable` PLUS A STATE DESCRIPTION, NOT `selectable` ────────────────────────────────────
 *
 * A disclosure is not a choice among options: `selectable` makes TalkBack announce "selected" /
 * "not selected", which for a section that opens and closes is the wrong noun and gives a reader no
 * idea that pressing it reveals something. `stateDescription` announces "Expanded" / "Collapsed",
 * `onClickLabel` says what the press will DO, and [Role.Button] says what kind of thing it is. The
 * chevron beside the words carries none of the three to somebody who cannot see it. This is the same
 * arrangement `DesignReviewScreen`'s rating ledger uses, and for the same reason.
 *
 * ── THE CHEVRON TURNS, AND IT IS NOT THE ONLY THING SAYING WHICH WAY IS OUT ───────────────────
 *
 * The rotation is decoration on top of the state description and, when collapsed, on top of the
 * summary line underneath it — nothing here exists only as motion, and nothing here is carried by
 * colour. The turn collapses to [snap] under `LocalAppPreferences.reducedMotion`, exactly as
 * [DwGalleryFloor]'s fill does.
 */
@Composable
internal fun DwPanelDisclosureHeader(
    /** Decorative, always: the title beside it is what carries which card this is. */
    icon: ImageVector,
    /**
     * The card's name, and the SAME name in both states.
     *
     * A control whose label changes when you press it reads as a different control — which is what
     * the tracing panel used to do, calling itself "Trace a photographed sketch into line art" shut
     * and "Trace a sketch into line art" open. One title per card, chosen once, and chosen to be the
     * portal's spelling where the portal has one — this card's own title matches its twin at
     * `MeasureFromPhotoCard.tsx:164` character for character. See [DW_TRACE_CARD_TITLE] for the
     * tie-break that rule settles.
     */
    title: String,
    expanded: Boolean,
    toggleEnabled: Boolean,
    /** What TalkBack is told the press will DO when the card is shut. Each card's own noun. */
    expandAction: String,
    /** The other direction of the same sentence. */
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
            // The 48dp floor this app applies wherever a control was thought about (see
            // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt). It is a touch target, not decoration.
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
            // `weight(1f)` AND NOT A FIXED WIDTH, which is what keeps this row honest at the largest
            // font scale: the title wraps to two or three lines, the row grows with it, and the
            // chevron and the close button stay on it rather than being pushed off the edge.
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // THE EXISTING DOOR, KEPT RATHER THAN REMOVED. A button is a merging semantics node of its
        // own, so it stays a separate TalkBack target inside this clickable row instead of being
        // swallowed by it, and it consumes its own taps so pressing it cannot also fire the row.
        if (expanded) DwPanelCollapseButton(prominent = false, title = title, onClick = onToggle)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            // Decorative: the row's own state description already says which state this is, in words.
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier
                .size(20.dp)
                .rotate(turn),
        )
    }
}

/**
 * The way out of a derivation card, drawn TWICE on purpose — once in the header and once at the foot
 * of the contents.
 *
 * The report this answers was not "there is no close button"; there was one. It was that the only one
 * sat at the top of a card the designer had just scrolled to the bottom of. So the second is placed
 * after the work ends, and BOTH are this one composable saying [DW_PANEL_COLLAPSE_WORD], so the two
 * doors can never come to be labelled differently.
 *
 * ── AND IT IS EVERY CARD'S DOOR NOW ───────────────────────────────────────────────────────────
 *
 * The tracing panel and the straightening panel each had a hand-rolled copy of this, one of them with
 * a 16 dp icon against this one's 14, and neither had the second door at the foot — on surfaces that
 * are considerably taller than this one. `DwSketchTracePanel`'s open half is a frame chooser, a
 * comparator, two dozen control rows and an export card; its single door was at the top of all of
 * that. One composable, three cards, one word.
 *
 * ── AND THE FOOT'S COPY NAMES THE CARD, WHICH THE HEADER'S DOES NOT NEED TO ───────────────────
 *
 * `MeasureFromPhotoCard.tsx:475-477` states it and this client had it wrong until the cards were
 * stacked: *"A REAL BUTTON WITH THE CARD'S NAME IN IT, not a bare 'Close': at the foot of a long
 * panel there is no heading in view to say what would be closing, and this card is one of several
 * stacked disclosures on the tab."*
 *
 * **THAT BECAME TRUE HERE ON 2026-08-29 AND WAS NOT TRUE BEFORE IT.** Until the Upload tab gained
 * [DwSketchDerivationSection] these three cards were never on one screen — each sat on its own
 * registry field in the stage form, with other fields between them — so one full-width "Close" was
 * unambiguous by position. Three of them now stack in one column, and three identical full-width
 * buttons at three card feet is a designer pressing the wrong one and losing an open comparator.
 *
 * The header's copy stays bare, deliberately and for the other client's reason: it sits ON the title
 * row, inside the same `Row` as the name it would be repeating, so naming the card there would print
 * it twice on one line. The web makes the same split — an icon-only close in its header
 * (`aria-label="Close the measuring panel"`) and `Collapse “{CARD_TITLE}”` at its foot.
 *
 * @param prominent the foot's copy is full width and outlined, because it has to be findable at the
 *   end of a long card; the header's is the compact text button that has always been there. The SHAPE
 *   differs, and so does whether the card is NAMED; the VERB does not.
 * @param title the card's own name, for the foot's copy. Unused by the header's, which is drawn
 *   beside it. Every caller passes the same constant its [DwPanelDisclosureHeader] does.
 */
@Composable
internal fun DwPanelCollapseButton(prominent: Boolean, title: String, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        // ONE VERB, TWO LENGTHS — and the verb is the constant, so neither door can be relabelled
        // without the other. See the note above for why only one of them carries the name.
        Text(
            if (prominent) "$DW_PANEL_COLLAPSE_WORD “$title”" else DW_PANEL_COLLAPSE_WORD,
            fontSize = 12.sp,
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
    /** Everything a designer typed or placed. Owned by the panel, so it outlives this composable. */
    config: DwMeasureConfig,
    /**
     * The photograph a host above chose, or null where this card owns the choice.
     *
     * Carried rather than re-derived from `supply` so the open half cannot come to a different answer
     * from the half that drives [DwMeasureConfig.followShared]. It decides one thing: whether the
     * chooser here is a plain list (this card's own) or the escape hatch (a host's).
     */
    sharedId: String?,
    onCollapse: () -> Unit,
    onPropose: (String, JsonElement?, String?) -> Unit,
) {
    val photo = photos.firstOrNull { it.id == config.photoId } ?: photos.first()
    val marks = config.marks
    val needed = config.needed

    /*
     * THE PIXELS, AND NOTHING ELSE, LIVE HERE.
     *
     * These five are the state a collapse destroys and the next expansion rebuilds, and that is the
     * intended trade rather than an oversight — see [DwMeasureConfig] for the other half of the split.
     * Marks and text are cheap and are kept up there; a decoded working copy is the largest allocation
     * this feature makes and is not.
     */
    var image by remember { mutableStateOf<DwDisplayImage?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    /*
     * NOT HOISTED, DELIBERATELY. This describes the press that just happened — a coercion the registry
     * refused — and a collapse is a different subject. Carried up into [DwMeasureConfig] it would
     * outlive the press and reappear minutes later over a card the designer has stopped thinking
     * about, describing a button they no longer remember touching.
     */
    var proposeError by remember { mutableStateOf<String?>(null) }

    /**
     * Decode the chosen photograph, off the main thread, one at a time.
     *
     * `Dispatchers.Default` and never the main thread: [DwImageDecode] measures its own decode at a few
     * hundred milliseconds and sometimes approaching a second on a loaded handset, which on the main
     * thread is an unmistakable freeze. Keyed on the photograph's id, so switching photographs releases
     * the previous working copy before the next is decoded rather than holding two.
     *
     * THE PHOTOGRAPH IS ADOPTED RATHER THAN THE MARKS CLEARED. This effect re-runs on every expansion,
     * because
     * expanding is what puts this composable back into the composition — so the unconditional
     * `marks.clear()` that stood here until 2026-08-28 would, now the marks outlive the collapse, wipe
     * the designer's work every time they reopened the card. That is the whole substance of the
     * report. Clearing belongs to a CHANGE OF PHOTOGRAPH, and [DwMeasureConfig.usePhotograph] is the
     * guard that tells the two events apart. The decode itself still happens on every entry, as
     * intended: the bitmap is the thing this panel does not keep.
     */
    LaunchedEffect(photo.id) {
        config.usePhotograph(photo.id)
        image = null
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
    // disagree the first time the panel is opened after a rotation. The box also changes shape once
    // per decode now — it takes the photograph's aspect ratio, see the viewport below — and this is
    // what re-centres for that.
    LaunchedEffect(image, boxSize) {
        zoom = 1f
        pan = clampPan(Offset.Zero, 1f)
    }

    /** Seed any mark this mode needs that does not exist yet, once the working copy's size is known. */
    LaunchedEffect(image, config.mode) {
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
            // Collapsing is ALWAYS allowed, even where the field itself is read-only: it writes
            // nothing, and a designer who can see a configured card must be able to put it away
            // again. Expanding keeps its gate (see the collapsed half); this is the other direction.
            toggleEnabled = true,
            expandAction = DW_MEASURE_EXPAND_ACTION,
            collapseAction = DW_MEASURE_COLLAPSE_ACTION,
            onToggle = onCollapse,
        )

        /*
          ONE PHOTOGRAPH IS NOT A CHOICE, AND THE ESCAPE HATCH IS NOT DRAWN OVER ONE.

          The chooser this replaces has always been gated on `photos.size > 1` — the `else` branch
          below still is — for the reason [dwMeasureSummary] gives about naming the photograph: one
          of something is not a decision anybody made. The hatch lost that gate when it was written,
          and a sketch with ONE photograph is the commonest record this feature has: the entity
          declares a single image field and a designer attaches a single frame to it.

          WHAT THAT PUT ON THE SCREEN. A disclosure called "Measure a different photograph", over a
          paragraph explaining that the sheet worth tracing and the photograph with a ruler beside the
          object are often two different pictures, which opens onto a chip row containing exactly one
          chip: the photograph already being measured, labelled "chosen above". A control that offers
          an act this record cannot perform is the same defect [dwNoPhotographSentence] was rewritten
          for one file over — naming a capability a designer will then go looking for — and here it is
          worse by a degree, because it is not a sentence about a control, it IS the control.

          `|| inForce` AND NOT THE COUNT ALONE, so this gate can never strand anybody. An override is
          only settable from the chip row, which needs two photographs — but the second can be
          DETACHED afterwards, and the effect in the panel that drops a dead override is one
          composition behind the list. For that one frame the count says "hide" and the override says
          "in force", and hiding is the wrong answer: it would take the "Go back to the photograph
          chosen above" button off a card that is announcing an override directly above it.
        */
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

        // Two real buttons rather than a dropdown, because the choice IS the explanation and a designer
        // has to read both to make it.
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

        // THE ASSUMPTION, ON SCREEN. See the file header for why this is not a comment.
        if (config.mode == DwMeasureMode.SCALE) {
            DwPanelNote(
                warning = true,
                text = "This is only true when the reference and the thing you are measuring lie in the " +
                    "same flat plane, square to the camera. A scale card lying on a table and a pot " +
                    "standing on that table are not in one plane, and a dimension measured across a " +
                    "tilted object comes out wrong — by however much the angle happens to be, with " +
                    "nothing later able to tell. If the photograph is at an angle, use the four-corner " +
                    "method instead.",
            )
        } else {
            DwPanelNote(
                warning = false,
                text = "Mark the four corners of the rectangle in order around it — 1, 2, 3, 4 walking " +
                    "round the edge, not in reading order. The tilt of that surface is then corrected " +
                    "for exactly. The object still has to be lying on that surface: what is corrected " +
                    "is the angle of the plane, not the height of something standing up off it.",
            )
        }

        DwPanelLabel("Marks — tap the photograph to place, drag a handle or nudge to adjust")
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

        /* ── The photograph ─────────────────────────────────────────────────────────────────── */

        /*
         * THE VIEWPORT IS THE SHAPE OF THE PHOTOGRAPH. IT WAS A FLAT `.height(300.dp)` UNTIL
         * 2026-08-28, AND THAT ONE LITERAL IS WHERE "EXCESSIVE EMPTY SPACE ABOVE AND BELOW" CAME FROM.
         *
         * It produced the complaint twice over, which is why trimming the number would not have fixed
         * it:
         *
         *  1. BEFORE THE DECODE LANDS there is nothing to draw — `image` is null for the few hundred
         *     milliseconds [DwImageDecode] takes, and stays null for good on the branch where this
         *     device cannot open the bytes at all — and the box still reserved 300dp, most of what is
         *     left of a handset screen, around one centred line of text.
         *  2. AFTER IT LANDS the box's own shape (full width by 300dp, so roughly 6:5 on a phone) has
         *     nothing to do with the photograph's, and `fit` letterboxes the picture inside it. Every
         *     landscape frame — which is what an object photographed beside a scale card usually is —
         *     got grey bands top and bottom: about 30dp each at 4:3 and about 60dp each at 16:9.
         *
         * So there are two shapes now and neither reserves anything. With a working copy the box takes
         * that copy's OWN aspect ratio, clamped to [MIN_VIEWPORT_RATIO]..[MAX_VIEWPORT_RATIO], and
         * inside that band there is no empty band on any edge at all. Without one it wraps whichever
         * sentence it is showing, over a floor of [EMPTY_VIEWPORT_MIN_HEIGHT] — a floor set by the two
         * zoom buttons parked in this box's corner, which are the one thing in it that must not be
         * clipped, and not by any picture.
         */
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
                        "The photograph being measured. Tap it to place the mark selected above; " +
                            "drag a handle to move it; pinch to zoom."
                }
                // Tap places the mark the designer is currently working on and moves the cursor to the
                // next — the four-corner case is six marks, and reaching for a chip between each would
                // make it eleven actions instead of six. The zoom buttons and the mark handles are
                // CHILDREN of this box, and a child consumes the event before this detector sees it,
                // so neither of them can place a mark by accident.
                // `pan` and the active mark are deliberately NOT keys: both are read live, through the
                // state they live in, at the moment the tap arrives — so listing them would tear this
                // detector down and rebuild it on every frame of a pan for no benefit at all.
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
                            // The two ends of the object being measured are drawn hollow so the
                            // reference and the target read apart at a glance; the badge says which.
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

        if (bitmap != null) {
            DwNudgePad(active = config.active, enabled = enabled, displayScale = displayScale) { delta ->
                val current = marks[config.active]?.point ?: return@DwNudgePad
                setMark(config.active, DwPoint(current.x + delta.x, current.y + delta.y))
            }
        }

        /* ── The known size ─────────────────────────────────────────────────────────────────── */

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
                        // A PRESET FILLS IN TWO BOXES; IT IS NOT A THING THAT IS CURRENTLY TRUE.
                        // Nothing is preselected here on purpose (see [SCALE_PRESETS]), so a chip
                        // announcing "Not selected" would be reporting a state these do not have —
                        // and would suggest a designer had failed to pick one.
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
                        // An action, not a choice. See the scale presets above.
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
            mode = config.mode,
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
                    // `result` is the very computation the button is printing, so the technique
                    // reported is the one that produced THIS number — not the mode the panel happens
                    // to be showing when the press lands.
                    //
                    // NARROWED RATHER THAN ASSERTED. A `DwMeasureResult` is either a Measurement or
                    // a Refusal, and only the first has a method; the button cannot be reached from
                    // a Refusal (the readout returns early on one), so `as?` yields null on a state
                    // that does not occur instead of throwing on a state somebody later makes occur.
                    onPropose(
                        target.field.key,
                        coerced.value,
                        (result as? DwMeasureResult.Measurement)?.method,
                    )
                }
            },
        )

        /* ── The way out, at the point the work ends ────────────────────────────────────────── */

        /*
         * THE SECOND DOOR. The header's has always been there and is still there; this one exists
         * because the header's is a whole card away by the time a designer has placed six marks, typed
         * the reference and read the answer. It sits AFTER the propose buttons on purpose: that is the
         * moment the work is finished, and it is the moment the report describes somebody scrolling
         * all the way back up from.
         */
        Text(
            "Everything above is kept when this is closed — the marks, the reference and the " +
                "photograph you chose. Only the picture itself is decoded again next time.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        DwPanelCollapseButton(prominent = true, title = DW_MEASURE_CARD_TITLE, onClick = onCollapse)
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The escape hatch
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "Measure a different photograph" — the way out of the shared choice, for this card only.
 *
 * ── WHY THIS IS NOT SIMPLY THE CHOOSER CHIPS AGAIN ────────────────────────────────────────────
 *
 * `MeasureFromPhotoCard.tsx:792-812` states it and every clause of it holds here: *"It is folded
 * away, it is named for what it is, and it says what it does not do. Left open beside the shared card
 * above it, two pickers of equal weight is what the designer was complaining about; hidden behind a
 * press, it is a way out of the default rather than a rival to it. And the default is stated in the
 * same breath, so a designer who opens it by accident learns that they did not need to."*
 *
 * ── AND AN OVERRIDE IN FORCE IS NEVER FOLDED AWAY ─────────────────────────────────────────────
 *
 * The other client's rule, kept: *"a control that is doing something must be visible"*. While this
 * card is pointed somewhere the tracing panel is not, the card SAYS SO at the top of itself, names
 * the photograph, and offers the way back — because two cards quietly working from two pictures is
 * the exact failure one shared choice was introduced to prevent, and an override folded behind a
 * chevron is how it would happen anyway.
 *
 * ── THE ONE PLACE THE TWO CLIENTS GENUINELY DIFFER ────────────────────────────────────────────
 *
 * There, the different photograph is a file the designer picks and that is on no record at all —
 * "displayed and measured and then forgotten". Here it is a different one of the RECORD's own
 * photographs, because on this client a panel is only ever handed a path to something
 * `DwMediaBridge.attach` already imported (see `FieldRenderer.kt:126-134`, and
 * `DwSketchDerivationPhoto`'s header for the whole argument). The act, its name and its promise are
 * the same; the SET is not, and the copy below says which set it is rather than repeating a sentence
 * about filing that would be false here.
 *
 * ── AND WHY IT IS NOT OFFERED ON THE PROTOTYPES HALF, WHERE THE OTHER CLIENT DOES OFFER IT ────
 *
 * `UploadTabHost.tsx:1521-1529` transfers `onUseDifferentPhoto` to its Prototypes half and gives a
 * good reason: *"the photographs a prototype is judged by and the photograph with a ruler beside it
 * are rarely the same picture — one is a clean turn against a plain background, the other has a scale
 * card lying in the frame."* **That reason is right, and the control it asks for does not exist on
 * this client — because there, "a different photograph" means one that is on NO record, and here
 * every photograph a panel can see is on the record already.**
 *
 * So on a prototype the set this hatch would choose from and the set the card's own chooser already
 * offers are THE SAME SET, and it would fold a plain chip row behind a disclosure and a paragraph for
 * no gain. The hatch earns its place on the sketches half only because there is a shared default
 * above it to depart from, and departing from it is a thing worth naming and worth reporting on the
 * collapsed card. With no shared default there is nothing to depart from: the chips ARE the choice.
 *
 * The designer's remedy on this client is the same one every photograph here goes through — attach
 * the ruler frame with the capture card, then choose it in the chips. What is lost against the other
 * client is measuring a picture that is filed nowhere, which this client cannot do on any surface and
 * has never claimed to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwMeasureDifferentPhotograph(
    photos: List<DwMediaItem>,
    config: DwMeasureConfig,
    /** What the host chose — what "go back" goes back to. */
    sharedId: String,
    /** What this card is measuring right now, shared or not. */
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                // The other client's sentence, with its one false clause replaced. There: "…and
                // neither of them has been filed." Here both photographs are already on the record,
                // so what is owed is the fact that this card changes neither.
                "“${chosen.displayName}” is being measured here and nowhere else. The tracing panel " +
                    "above is still working from the photograph chosen at the top of this section, " +
                    "and nothing on this card changes, moves or detaches either of them.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            // A REAL CONTROL AND NOT A TAPPABLE ROW, with the same three things the card headers owe
            // a screen reader: what state it is in, what the press will do, and that it is a button.
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
                        // THE SAME NAME AS ON THE OTHER CLIENT, which is the half of this that must
                        // not vary: it is one act, and a designer who learned it in a browser has to
                        // find it under the same words on the handset.
                        DW_MEASURE_DIFFERENT_PHOTOGRAPH,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        DW_MEASURE_DIFFERENT_WHY,
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    // Decorative: the row's own state description says which state this is, in words.
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
                            // NAMED AS THE SHARED ONE ON ITS OWN CHIP, so choosing "back" and
                            // choosing "the same picture again" are visibly one act rather than two
                            // that could leave the override standing over the right photograph.
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
                // Said under the chips rather than only in the header, because this is where the
                // press happens: a change of photograph is a change of subject, and the marks belong
                // to the subject.
                "Choosing a different photograph clears the marks — they are positions on one " +
                    "picture, and carrying them across would put the reference somewhere nobody aimed.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
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
                Text(DW_MEASURE_BACK_TO_SHARED, fontSize = 12.sp)
            }
        }
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

        DwPanelLabel("Propose this into")
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
            // THE SENTENCE IS THE OTHER TWO CARDS'; THE SHAPE IS NOT, AND THAT DIFFERENCE IS REAL.
            // Those cards have ONE destination and print one bordered [DwPanelNote] under it. This
            // card has one button PER length field — four of them on a prototype — and four bordered
            // boxes interleaved with four buttons would break the list of destinations into eight
            // things a designer has to re-read as a list. So the warning stays a line under the
            // button it belongs to, in the same warning colour, saying the same words in the same
            // order. See [dwPanelReplaceWarning] for what was wrong with the words before.
            dwPanelReplaceWarning(current, DwPanelHolds.VALUE)?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
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
 *
 * INTERNAL, AND TAKING STRINGS RATHER THAN A [DwMarkId]. [DwSketchRectifyPanel] puts four handles on a
 * photograph for exactly the same reason and with exactly the same accessibility contract, and a
 * second copy of this would be a second place for the 48dp floor, the consumed drag and the
 * "not placed yet" sentence to drift out of agreement. The two panels differ only in what the marks
 * are called, so that is what is passed in.
 *
 * @param key what the two `pointerInput` blocks are keyed on — the caller's own identity for this
 *   handle. A key that changed every frame would tear the gesture detectors down mid-drag.
 * @param outlined draws the disc hollow (the object's two ends here; nothing in the sketch panel),
 *   which is decoration ON TOP of [badge] and never the thing carrying which mark this is.
 */
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
            // Read in the LAYOUT phase, so a pan or a pinch moves every handle without recomposing
            // the panel around it.
            .offset {
                val view = positionInView()
                IntOffset((view.x - 24.dp.toPx()).roundToInt(), (view.y - 24.dp.toPx()).roundToInt())
            }
            .size(48.dp)
            .semantics {
                contentDescription = name +
                    (if (placed) "" else ", not placed yet") +
                    ". Drag it, or select it and use the nudge arrows below the photograph."
            }
            // Selecting a handle consumes the tap, so it cannot also fall through to the photograph
            // and place whichever OTHER mark happened to be active.
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSelect() }
            }
            .pointerInput(key, enabled) {
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
                .background(if (outlined) discColor else markColor, CircleShape)
                .border(
                    BorderStroke(if (isActive) 3.dp else 2.dp, if (outlined) markColor else discColor),
                    CircleShape,
                ),
        ) {
            Text(
                badge,
                color = if (outlined) markColor else discColor,
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

/** One arrow of a nudge pad. Shared with [DwSketchRectifyPanel], which nudges corners the same way. */
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

/** Internal for the same reason [DwMarkHandle] is: [DwSketchRectifyPanel] zooms the same way. */
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

/** The small caption above a group of controls. Shared with [DwSketchRectifyPanel]. */
@Composable
internal fun DwPanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/**
 * A chip, drawn selected or not — and, since 2026-08-29, SAYING which it is.
 *
 * Shared by all four derivation surfaces, so they have one idea of what a chip is.
 *
 * ── WHAT THIS KDOC USED TO CLAIM, AND WHY IT WAS NOT TRUE ─────────────────────────────────────
 *
 * It read: *"`selected` is carried by the FILL and by the word inside it — a preset chip is never
 * selected, and a mark chip says 'placed' or 'not placed' in words next to its badge, so nothing
 * here depends on telling two purples apart in direct sunlight."*
 *
 * **That was true of the mark chips and of nothing else.** The mark chips do carry their state in
 * words. The PHOTOGRAPH chips do not — their label is a file name — and neither do the mode chips,
 * the shape chips, the unit chips or the engine's option chips. On every one of those the selection
 * was a fill and only a fill: invisible in greyscale, invisible to a designer who cannot tell the
 * two purples apart in a courtyard at noon, and **silent to TalkBack**, which read out a row of
 * identically-shaped buttons with nothing to say which one was in force.
 *
 * That mattered most on the newest of them. [DwSketchSharedPhotograph]'s chips answer *which
 * photograph is every card on this record working from* — the whole point of the shared section —
 * and a screen reader was given no way to hear the answer off the control that sets it.
 *
 * ── SO THE STATE IS ANNOUNCED, IN THE SAME GRAMMAR [DwPanelDisclosureHeader] USES ─────────────
 *
 * `stateDescription`, exactly as that header announces "Expanded" / "Collapsed", and for the same
 * reason: it is one string that TalkBack reads after the label, it needs no role change, and it
 * cannot fall out of step with the fill because both are read off this one parameter.
 *
 * **"Selected" IS THE RIGHT NOUN HERE AND THE WRONG ONE THERE**, which is worth stating because
 * [DwPanelDisclosureHeader] argues against it at length. A disclosure is not a choice among options,
 * so "selected" tells a reader nothing about what pressing it reveals. A chip in a row of chips IS
 * a choice among options, and "selected" is what it is.
 *
 * @param isChoice false for a chip that ACTS rather than chooses — the scale and rectangle presets,
 *   which fill in two text boxes and are never "the current one". Announcing "not selected" on those
 *   would be a state description for a state they do not have, and would tell a designer they had
 *   failed to select something that cannot be selected. They are ordinary buttons that happen to be
 *   chip-shaped, and they are announced as ordinary buttons.
 */
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
    // 40dp AND NOT THE APP'S 48, DELIBERATELY: chips come in rows of four to eight inside an already
    // tall card, they are separated by 6dp of their own, and Material's own chip metrics stop here.
    // The floor still grows with the font scale, because it is a minimum rather than a height.
    val shape = Modifier
        .heightIn(min = 40.dp)
        .then(announce)
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, fontSize = 12.sp)
        }
    }
}

/**
 * The assumption card, and the refusal card. Same shape; the colour says how loud it is.
 *
 * Shared with [DwSketchRectifyPanel], whose refusals come from the same kind of place — marks that do
 * not support the thing being asked for.
 */
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
            DwPanelChip(
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
