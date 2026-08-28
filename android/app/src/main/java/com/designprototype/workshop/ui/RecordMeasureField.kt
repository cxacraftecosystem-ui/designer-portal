package com.designprototype.workshop.ui

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.MEASUREMENT_GRID_PURPOSE
import com.designprototype.workshop.ui.designworkshop.DwMeasureTarget
import com.designprototype.workshop.ui.designworkshop.DwMediaItem
import com.designprototype.workshop.ui.designworkshop.DwPhotoMeasurePanel
import com.designprototype.workshop.ui.designworkshop.dwMeasurableLengthFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Measure a dimension from a photograph" ON A RECORD FORM — the adapter, and NOTHING ELSE.
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * The product and tool record forms document a physical object's dimensions in inches, and the only
 * machine route they have ever offered is `GridMeasurementSection` in `MainActivity.kt`, which posts
 * the photograph to `POST /media/analyze-measurement` and has a vision model ESTIMATE the number —
 * the prompt's own verb. That route costs money per call, cannot be re-derived by anybody afterwards
 * (`MeasurementMethod.VISION_MODEL.reproducible` is False, and says why), and, being network-only,
 * fails every single time in the courtyard where the object is actually in the designer's hands.
 *
 * Three files away this app already carries the deterministic answer. `DwPhotoMeasure` is a port of
 * `frontend/lib/photoMeasure.ts` — the authority, as `DwPhotoMeasure`'s own header says — and
 * `DwPhotoMeasurePanel` is the screen over it: marks a person places, a ratio or a homography, an
 * error bar, and a button. It runs entirely on this device and makes no network call at all. But it
 * is mounted only on design-workshop STAGE fields, because it is registry-driven, and a record form
 * has columns rather than a registry. **That asymmetry is the only thing between the record forms and
 * a free, offline, re-derivable measurement, and closing it is what this file does.**
 *
 * THE INSIGHT THAT MAKES IT CHEAP: the grid sheet is itself a perfect deterministic reference. A
 * 1-inch grid means the designer can mark across N squares and say "that is N inches" — a scale
 * reference, typed once, with no model, no request and no per-call cost. The photograph the vision
 * route wants is ALREADY a reference photograph; it only ever needed measuring rather than inferring.
 *
 * ── THERE IS NO GEOMETRY IN THIS FILE, AND THERE MUST NEVER BE ────────────────────────────────
 *
 * No scale factor, no pixels-per-inch, no unit conversion, no error bar. Every one of those already
 * exists exactly once — `DwPhotoMeasure.measureBySameScale`, `measureByRectification`,
 * `convertLength`, `roundToUncertainty` — and each is pinned value-for-value against
 * `photoMeasure.ts` by `DwPhotoMeasureTest`. An adapter that re-derived any of them would be the
 * beginning of a THIRD implementation of the same plane geometry, differing from the other two
 * silently and only in the fourth digit, in a number printed on a costing sheet nobody can
 * re-measure. So what is here is a translation of NAMES: a record-form column becomes a [FieldDto]
 * the panel already knows how to write into, and the panel's answer comes back as the text the
 * form's box already holds.
 *
 * THE ONE PIECE OF ARITHMETIC BELOW IS NOT GEOMETRY — IT IS THE COLUMN. [DW_RECORD_DECIMALS] and the
 * rounding at [dwRecordProposalText] come from the storage layout (`@db.Decimal(10, 2)`) and from
 * nothing about the photograph, which is why they are allowed here and the geometry is not. The web
 * makes the identical cut in the identical place: `frontend/components/media/recordMeasure.ts`
 * rounds in the record adapter, while its stage panel does not round at all.
 *
 * ── IT PROPOSES; IT DOES NOT WRITE ────────────────────────────────────────────────────────────
 *
 * Inherited rather than re-implemented. The button, the figure printed on the button, the "Currently
 * X. This replaces it." warning and the refusal to show any reading before every mark has been moved
 * all live in [DwPhotoMeasurePanel] under its heading "IT NEVER WRITES A DIMENSION BY ITSELF". This
 * file adds no write path of its own: [RecordMeasureField]'s `onPropose` fires only from that button.
 * Re-stating the discipline here would be a second place for it to drift out of agreement.
 *
 * ── MOUNTED ON BOTH FORMS — CORRECTED 2026-08-27 ──────────────────────────────────────────────
 *
 * This section used to read: *"UNWIRED ON PURPOSE, AS OF 2026-08-27. Nothing references this file
 * yet. The two mounts belong in `ProductForm` and `ToolForm` in `MainActivity.kt`, which another
 * workstream was editing when this landed … One hit (this declaration) means the mounts are still
 * outstanding; three means they landed."* That was true for part of one day. The two mounts landed
 * later the same day, so the count it told you to make now answers with more than three and the
 * instruction reads as a failure. Re-check with:
 *
 *     grep -rn "RecordMeasureField(" android/app/src/main/java/
 *
 * Four hits, three of them call-shaped: this declaration, the two mounts (`MainActivity.kt`, the
 * product form and the tool form), and the line you are reading, which the pattern matches itself.
 * Counted by running that grep on 2026-08-27. One call-shaped hit means a mount has been removed.
 *
 * ── AND IT NOW REPORTS HOW IT MEASURED, NOT ONLY WHAT — 2026-08-27 ────────────────────────────
 *
 * `onPropose` carries a third argument, the technique. This file still computes no geometry and
 * still decides nothing: the value is `DwPhotoMeasure.Result.method`, passed from the panel to the
 * caller untouched, so that a record saved from a proposal can say `PHOTO_GEOMETRY` and name the
 * geometry a later reader would have to repeat. What the caller does with it is
 * `DwMeasurementMarkers`' business, including the rule that drops the claim the moment somebody
 * types over the number.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * A record-form column, as the stage panel needs to see it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One dimension COLUMN of a record form, and the unit that column is stored in.
 *
 * [unit] IS THE LOAD-BEARING FIELD and it is not decoration. The stage registry declares a unit on
 * every measurable field, and [dwMeasurableLengthFields] refuses any field whose unit
 * `DwPhotoMeasure.LENGTH_UNITS` cannot convert — which is what stops a centimetre figure being
 * proposed into a weight in grams. A record form has no registry to declare anything, so the unit has
 * to be asserted HERE, and asserting one that is not true of the column is the same silent, plausible,
 * uncorrectable error by a shorter path.
 *
 * So: a column whose unit is not actually known does not get a [DwRecordDimension]. See
 * [TOOL_MEASURE_DIMENSIONS] for the one that is currently excluded on exactly that ground.
 */
@Immutable
data class DwRecordDimension(
    /** The request-body key — `lengthInches`, `breadthInches`, `heightInches`. */
    val column: String,
    /** What the form's own box is labelled, so the proposal button names the box it will fill. */
    val label: String,
    /** A key of `DwPhotoMeasure.LENGTH_UNITS`. Record dimensions are inches throughout. */
    val unit: String = "in",
)

/**
 * The product form's three dimension columns, in the order the form renders them.
 *
 * `ProductDocumentation` carries `lengthInches` / `breadthInches` / `heightInches`, and every one of
 * them says its unit in its own name — which is why all three are here and why the tool's list below
 * is shorter.
 */
val PRODUCT_MEASURE_DIMENSIONS: List<DwRecordDimension> = listOf(
    DwRecordDimension("lengthInches", "Length (inches)"),
    DwRecordDimension("breadthInches", "Breadth (inches)"),
    DwRecordDimension("heightInches", "Height (inches)"),
)

/**
 * The tool form's dimension columns — the same three as the product's, since 2026-08-27.
 *
 * ── WHY THE TOOL'S OTHER "Height" BOX IS STILL NOT HERE ───────────────────────────────────────
 *
 * `ToolDocumentation` carries TWO heights and only one of them can be a measurement destination.
 * `heightInches` below is the one this proposes into: it states its unit in its own name, exactly as
 * length and breadth do. The tool form's other box, bound to `ToolCreateRequest.height`, is a bare
 * `Decimal` that declares no unit anywhere — not in the column name, not in the schema, not on the
 * label the designer reads — and it keeps holding whatever was typed, in a unit nothing records.
 *
 * That is not an oversight to tidy up later. A geometric measurement's whole advantage over the
 * vision model is that the number can be trusted, and a trustworthy number in a field that does not
 * say what it measures is not better than a bad one — it is the same costing error with more
 * confidence behind it. So the unit-less column is never offered, and the migration that added
 * `heightInches` (`20260827120000_tool_height_inches`) says at length why it did not migrate the old
 * values across: nothing in the database can say what unit they are in.
 *
 * `RecordMeasureFieldTest` asserts that `height` is never a destination here.
 */
val TOOL_MEASURE_DIMENSIONS: List<DwRecordDimension> = listOf(
    DwRecordDimension("lengthInches", "Length (inches)"),
    DwRecordDimension("breadthInches", "Breadth (inches)"),
    DwRecordDimension("heightInches", "Height (inches)"),
)

/* ────────────────────────────────────────────────────────────────────────────
 * The translation: columns → registry fields the panel already understands
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A synthetic [FieldDto] per column, run through the registry's OWN eligibility test.
 *
 * THE POINT OF THE ROUND TRIP. It would be one line shorter to build [DwMeasureTarget]s directly and
 * skip [dwMeasurableLengthFields] — and that line is the whole safety property. That function is
 * where "this unit is one `DwPhotoMeasure` can convert" is decided for the stage surface, reading
 * `DwPhotoMeasure.LENGTH_UNITS`, the very map the conversion goes through. Building targets by hand
 * here would put a SECOND answer to that question in the app, so a unit this module cannot convert
 * could become a destination on record forms while remaining impossible on stages. One map, one
 * predicate, both surfaces.
 *
 * `type = "DECIMAL"` because that is what the column is, and because it is what `DwValues.coerce`
 * must do with the proposal text — an INT would silently truncate 12.4 inches to 12.
 *
 * `minValue = 0.0` mirrors the server's `Field(default=None, ge=0)` on every one of these columns. A
 * measured distance cannot be negative, so this can only ever fire on a bug — but when it does it
 * fires on THIS screen, naming the box, rather than as a 422 after the designer has left the cluster.
 */
internal fun dwRecordMeasureTargets(dimensions: List<DwRecordDimension>): List<DwMeasureTarget> {
    // Insertion order is kept, and is load-bearing: `dwMeasurableLengthFields` iterates `values`, and
    // the order it returns is the order the proposal buttons appear in. Keeping it the form's own
    // top-to-bottom order is what makes "the third button is the third box" true without anybody
    // having to check.
    val siblings: Map<String, FieldDto> = LinkedHashMap<String, FieldDto>().apply {
        dimensions.forEach { dimension ->
            put(
                dimension.column,
                FieldDto(
                    key = dimension.column,
                    label = dimension.label,
                    type = "DECIMAL",
                    unit = dimension.unit,
                    minValue = 0.0,
                ),
            )
        }
    }
    return dwMeasurableLengthFields(siblings)
}

/**
 * What the form's boxes hold right now, in the shape the panel reads them in.
 *
 * The panel uses this for one sentence — *"Currently “14”. This replaces it."* — which is the warning
 * that stops a proposal quietly overwriting a number somebody measured with callipers ten minutes
 * ago. A blank box is omitted rather than stored as `""`, because `DwValues.text` of a blank primitive
 * and of an absent key are the same empty string to the panel, and an entry carrying no information
 * is an entry that can go wrong.
 */
internal fun dwRecordRowValues(current: Map<String, String>): Map<String, JsonElement> =
    current.mapNotNull { (column, text) ->
        text.trim().takeIf { it.isNotEmpty() }?.let { column to JsonPrimitive(it) }
    }.toMap()

/**
 * How many decimal places a dimension column on a product or tool record can actually hold.
 *
 * READ OFF THE COLUMN AND NOTHING ELSE. `ProductDocumentation.lengthInches` / `breadthInches` /
 * `heightInches` and `ToolDocumentation.lengthInches` / `breadthInches` / `heightInches` are every
 * one of them `@db.Decimal(10, 2)` in `backend/prisma/schema.prisma`, and Postgres rounds a third
 * decimal away on assignment with nobody told — no 422, no warning, no trace. The server does not
 * catch it either: `backend/app/schemas/records.py` bounds these fields with `ge=0` and declares no
 * `decimal_places`, so 4.213 is accepted and 4.21 is stored.
 *
 * THE SAME CONSTANT, WITH THE SAME NAME AND THE SAME VALUE, IS `COLUMN_DECIMALS` IN
 * `frontend/components/media/recordMeasure.ts`. If the column ever widens, both move together.
 *
 * Re-check: `grep -c "Decimal(10, 2)" backend/prisma/schema.prisma` — 11 matching lines when counted
 * on 2026-08-27, six of them the dimension columns named above (three inside `model
 * ProductDocumentation` and three inside `model ToolDocumentation`).
 */
internal const val DW_RECORD_DECIMALS: Int = 2

/**
 * The panel's figure fitted to [DW_RECORD_DECIMALS], or null when it already fits.
 *
 * `BigDecimal` rather than a `Double` round, because what arrives here is decimal TEXT and the whole
 * question is which decimal digit survives: `1.005 * 100` in binary floating point is
 * 100.49999999999999, so `(value * 100).roundToInt() / 100.0` answers 1.00 where the text the
 * designer was shown plainly reads 1.005. HALF_UP, the same direction as the web's `Math.round` on
 * a positive value.
 *
 * WHAT THIS DOES NOT CLAIM: bit-exact agreement with the web on a value sitting exactly on the
 * boundary. `recordMeasure.ts` rounds the raw converted `number` and this rounds the panel's already
 * quoted text, so the two pipelines can disagree in the second decimal on a figure whose binary
 * representation falls on the wrong side of .xx5. The drift this closes is the whole third digit,
 * which is systematic; the boundary is a single unit in the last place and is not fixable from this
 * side of the panel.
 *
 * Null on anything that is not a number, which is the honest answer for a value nobody clamped.
 */
private fun dwClampedToColumn(offered: String): BigDecimal? =
    offered.toBigDecimalOrNull()
        ?.takeIf { it.scale() > DW_RECORD_DECIMALS }
        ?.setScale(DW_RECORD_DECIMALS, RoundingMode.HALF_UP)

/**
 * The accepted proposal, as the text a record form's box holds — ROUNDED TO WHAT THE COLUMN HOLDS.
 *
 * ── WHY THE ROUNDING IS HERE AND NOT LEFT TO POSTGRES ─────────────────────────────────────────
 *
 * `DwPhotoMeasure.roundToUncertainty` quotes a value to the decimal place its own error bar reaches,
 * and caps that at FOUR (`min(4.0, …)`); a zoomed mark on a close-up routinely earns three. The box
 * it is proposed into is `@db.Decimal(10, 2)` — see [DW_RECORD_DECIMALS] — so an unclamped 4.213 is
 * saved, accepted, and stored as 4.21, with nothing on any screen saying a digit went. Meanwhile the
 * web port of this same adapter clamps to two and prints a sentence when it had to, so the identical
 * photograph and the identical marks produced "4.213" on the handset and "4.21" in the browser.
 *
 * Rounding here rather than at the column means the number the designer accepts is the number that
 * is stored, and [dwRecordProposalNote] is what puts the difference on screen.
 *
 * A VALUE THAT ROUNDS TO ZERO IS REFUSED, NOT STORED — the empty string, which [RecordMeasureField]
 * treats as "propose nothing". A stored `0.00` in a dimension column does not read as "under five
 * thousandths of an inch"; it reads as a measurement of nothing, and it is printed that way in the
 * report's dimensions cell. `recordMeasure.ts` refuses the same case for the same reason, and the
 * refusal is said out loud by [dwRecordProposalNote] rather than left as a button that did nothing.
 *
 * ── AND IT STILL MUST NOT GO THROUGH `numToText` ──────────────────────────────────────────────
 *
 * `MainActivity.numToText(Double?)` renders a whole number without its decimal point: 12.0 becomes
 * "12". Every other producer of a record dimension is a typed string or a database decimal, so that
 * is harmless for them and is why the helper reads the way it does. It is NOT harmless for this one.
 * A reading of 12.0 in ± 0.4 in is a claim about a tenth of an inch; "12" is a claim about an inch.
 * Dropping that digit throws away the only surviving trace of the measurement's quality, in the
 * direction of overstating it — which is why a figure ALREADY inside the column's two places is
 * returned exactly as the panel wrote it, trailing zero and all.
 */
internal fun dwRecordProposalText(proposed: JsonElement?): String {
    val offered = DwValues.text(proposed)
    val clamped = dwClampedToColumn(offered) ?: return offered
    return if (clamped.signum() == 0) "" else clamped.toPlainString()
}

/**
 * WHAT THE DESIGNER IS TOLD WHEN THE COLUMN COULD NOT HOLD WHAT WAS MEASURED. Null when it could.
 *
 * A cap that is applied silently is the defect, not the cap: the panel's own closing line tells the
 * designer that *"the number of digits is the only thing left saying how well it was measured"*, so
 * a digit removed on the way into the box has to be accounted for on the same screen. Said only when
 * it actually happened — a note under every proposal is noise that trains a reader past the one
 * proposal where it matters, which is `recordMeasure.ts`'s reason for the same choice.
 *
 * BOTH NUMBERS ARE IN IT, and the box is named, because the note appears below the panel rather than
 * on the button: "4.213" is on the button the designer just pressed and "4.21" is what is now in a
 * box further up the form, and a sentence that named neither would leave them to find the difference.
 */
internal fun dwRecordProposalNote(dimension: DwRecordDimension, proposed: JsonElement?): String? {
    val offered = DwValues.text(proposed)
    val clamped = dwClampedToColumn(offered) ?: return null
    if (clamped.signum() == 0) {
        return "That measures about $offered ${dimension.unit}, which rounds to zero in " +
            "“${dimension.label}” — that box holds $DW_RECORD_DECIMALS decimal places. A stored 0 " +
            "would read as “measured, and it is nothing”, so nothing was put in it."
    }
    return "“${dimension.label}” holds $DW_RECORD_DECIMALS decimal places, so the measured " +
        "$offered ${dimension.unit} went in as ${clamped.toPlainString()}."
}

/**
 * The chip label for one photograph in the panel's chooser.
 *
 * Position rather than filename, deliberately. The panel shows these only when there is more than one
 * photograph, on a phone, in sun, and what the designer needs is "which of the ones I just took"; a
 * camera's `IMG_20260827_113455.jpg` truncated to fit a chip answers a different question. It also
 * means this file needs no third copy of the `OpenableColumns.DISPLAY_NAME` query that
 * `WorkshopDraftStore` and `OfflineQueue` each already carry privately.
 *
 * THE GRID SHOTS ARE NAMED, because they are the ones most likely to have a reference in them — that
 * is what a grid sheet IS — and a designer scanning the chooser for the photograph with the graph
 * paper in it should not have to open each one to find out.
 */
internal fun dwRecordPhotoLabel(index: Int, total: Int, purpose: String?): String {
    val position = if (total > 1) "${index + 1} of $total" else "${index + 1}"
    return if (purpose == MEASUREMENT_GRID_PURPOSE) "Grid photo $position" else "Photo $position"
}

/* ────────────────────────────────────────────────────────────────────────────
 * The mount
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The deterministic measurement panel, on a record form.
 *
 * [photos] is the form's OWN attach-media batch (`MediaCaptureState.uris`) — every photograph the
 * designer has attached to this record, grid shots included, because `GridMeasurementSection` routes
 * its graph-paper captures into that same list. Nothing extra is captured for this panel and nothing
 * extra is uploaded: it READS the batch, and a photograph taken thirty seconds ago with no signal is
 * exactly as measurable as one that has finished uploading.
 *
 * It draws nothing when there is no image in the batch, which is [DwPhotoMeasurePanel]'s own behaviour
 * on an empty field, so a record with no photographs is unchanged.
 *
 * THE ONE THING IT DRAWS OF ITS OWN is [dwRecordProposalNote], under the panel: the button prints
 * the figure the error bar earned and the box can only hold two decimals of it, so on the proposals
 * where those differ the difference is stated instead of being left for nobody to notice. The note
 * is not remembered past the next proposal — it describes the press that just happened.
 *
 * @param purposes `MediaCaptureState.purposes`, read only to label the grid shots in the chooser.
 * @param onPropose fired ONLY from the panel's own button, with the column, the text to put in it,
 *   and which geometry produced it — `"SCALE"` or `"RECTIFIED"`, the two words the server's
 *   `GEOMETRY_TECHNIQUES` holds, straight off `DwPhotoMeasure.Result.method` with no mapping in
 *   between. The caller sends it as the `technique` of a `PHOTO_GEOMETRY` marker; see
 *   [com.designprototype.workshop.data.dwGeometryMarker], which drops any value it does not
 *   recognise rather than letting the server refuse the save.
 */
@Composable
internal fun RecordMeasureField(
    dimensions: List<DwRecordDimension>,
    current: Map<String, String>,
    photos: List<Uri>,
    purposes: Map<Uri, String>,
    enabled: Boolean = true,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
) {
    val targets = remember(dimensions) { dwRecordMeasureTargets(dimensions) }
    if (targets.isEmpty()) return
    val items = rememberMeasurablePhotos(photos, purposes)
    if (items.isEmpty()) return
    var columnNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DwPhotoMeasurePanel(
            photos = items,
            targets = targets,
            rowValues = remember(current) { dwRecordRowValues(current) },
            enabled = enabled,
            onPropose = { column, proposed, technique ->
                val text = dwRecordProposalText(proposed)
                // Cleared on every press, not only set: a note left standing from the previous
                // proposal would describe a rounding that did not happen to this one.
                columnNote = dimensions.firstOrNull { it.column == column }
                    ?.let { dwRecordProposalNote(it, proposed) }
                // A blank means one of two things, and both are already on screen. Either the
                // coercion refused — the panel has shown the reason in its own `proposeError` line —
                // or the figure rounded to zero in the column, which `columnNote` above has just
                // said in as many words. Writing "" over a number the designer typed would be a
                // silent deletion on top of a failure they can already see.
                //
                // AND NO MARKER IS EMITTED FOR IT EITHER, because this returns before the caller can
                // record one — which is the honest outcome: nothing was written, so there is nothing
                // whose method could be described.
                if (text.isNotBlank()) onPropose(column, text, technique)
            },
        )
        columnNote?.let { note ->
            Text(
                note,
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Bytes the panel can decode
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where the working copies live. Its own directory, so the sweep below can never widen. */
private const val WORKING_DIR = "measure-working"

/**
 * One resolved photograph: where its bytes can be decoded from, and whether we put them there.
 *
 * [copied] IS WHAT MAKES THE SWEEP SAFE. A `file:` Uri is used where it lies, so its path is the
 * designer's own photograph; deleting one on the way out would delete a capture the record has not
 * uploaded yet. Only the scratch copies this file made are ours to remove.
 */
private data class DwWorkingFile(val path: String, val sizeBytes: Long, val copied: Boolean)

/**
 * Turn the form's `Uri`s into things [DwPhotoMeasurePanel] can decode, and clean up after itself.
 *
 * ── WHY A COPY IS NEEDED AT ALL, WHICH IS THE ONE REAL COST OF THIS MOUNT ─────────────────────
 *
 * The panel decodes through `DwImageDecode.decodeForDisplay(path)`, which is `BitmapFactory
 * .decodeFile` underneath — a FILESYSTEM PATH. A stage field can always supply one, because
 * `WorkshopDraftStore.importMedia` has already copied every attachment into the workshop's media
 * directory before the field ever renders it. A record form has not: `MediaCaptureState.uris` holds
 * whatever the picker or the camera intent handed back, which is a `content:` Uri — a permission
 * grant, not a file, and one `decodeFile` cannot open.
 *
 * ── AND WHY THIS IS NOT A THIRD MEDIA COPIER ──────────────────────────────────────────────────
 *
 * `WorkshopDraftStore.importMedia` and `OfflineQueue.stageMedia` both copy a capture out of its Uri,
 * and both are DURABLE copies with a hash, an `fd.sync()` and a descriptor written down, because the
 * bytes they hold are the only copy of a photograph somebody cannot re-take. Neither can be reused
 * here — one files bytes under a design workshop a record form does not have, the other enrols them
 * in the upload outbox, which would queue a second upload of a photograph already in the batch.
 *
 * What is made here is the opposite kind of thing and is deliberately weaker: a SCRATCH copy, with no
 * hash, no descriptor and **no fsync**. Losing it costs a re-decode and nothing else, because the
 * original is still in `media.uris` and is still what gets uploaded and saved. Spending an fsync per
 * photograph — seconds, on the storage the camera is also writing to — to protect bytes that are
 * already safe somewhere else would be cargo-culting the discipline rather than applying it.
 *
 * A `file:` Uri is used WHERE IT LIES and never copied: the camera captures this app makes itself are
 * already real files, and copying one would double the largest allocation on the screen for nothing.
 *
 * ── ONE COPY PER PHOTOGRAPH, NOT ONE PER ATTACHMENT ───────────────────────────────────────────
 *
 * [resolved] is the reason this is not a loop. `MediaCaptureState.uris` grows as the designer
 * attaches, and this effect re-runs on every change to it; copying the whole batch each time turns
 * ten attachments into fifty-five copies of a 4 MB frame, on the storage the camera is writing to,
 * while the panel is open. So a Uri is resolved ONCE and remembered, entries whose Uri has left the
 * batch are deleted immediately rather than at dispose, and the label — which does move, because it
 * carries the photograph's position in a batch that is still growing — is rebuilt each pass from the
 * remembered file rather than being baked into it.
 *
 * ── AND THE COPIES ARE DELETED WHEN THE FORM LEAVES ───────────────────────────────────────────
 *
 * `cacheDir` is reclaimed by Android eventually, but "eventually" on a 32 GB field phone two weeks
 * into a study means these sit beside the captures competing for the space the camera needs. The
 * `onDispose` sweep deletes only the files THIS composition copied, by name, off the main thread.
 */
@Composable
private fun rememberMeasurablePhotos(
    uris: List<Uri>,
    purposes: Map<Uri, String>,
): List<DwMediaItem> {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DwMediaItem>>(emptyList()) }
    // Keyed by `uri.toString()` rather than by the Uri, because that string is also the id the panel
    // keys its decode on — one identity for the photograph, used in both places.
    //
    // CONCURRENT, and not for parallelism: nothing here runs in parallel. The effect below mutates
    // this on an IO thread and `onDispose` reads it on the main one, and cancelling a coroutine does
    // not interrupt a `copyTo` already in flight — so the two genuinely can overlap by the length of
    // one file copy. A plain map would leak an entry, or throw, in exactly that window.
    val resolved = remember { ConcurrentHashMap<String, DwWorkingFile>() }

    LaunchedEffect(uris, purposes) {
        items = withContext(Dispatchers.IO) {
            val live = uris.mapTo(mutableSetOf()) { it.toString() }
            // Attachments the designer has removed since the last pass. Deleted now rather than at
            // dispose: a form open for an hour while photographs are added and discarded would
            // otherwise accumulate every copy it ever made.
            resolved.keys.filterNot { it in live }.forEach { key ->
                resolved.remove(key)?.let { if (it.copied) runCatching { File(it.path).delete() } }
            }
            uris.mapIndexedNotNull { index, uri ->
                val key = uri.toString()
                val file = resolved[key]
                    ?: dwWorkingCopy(context, uri)?.also { resolved[key] = it }
                    ?: return@mapIndexedNotNull null
                DwMediaItem(
                    // THE ORIGINAL URI IS THE IDENTITY, not the copy's filename. The panel keys its
                    // decode on `photo.id` and clears every mark when it changes, so an id that moved
                    // would wipe a half-placed set of marks whenever the batch was touched.
                    id = key,
                    displayName = dwRecordPhotoLabel(index, uris.size, purposes[uri]),
                    absolutePath = file.path,
                    mediaType = "IMAGE",
                    sizeBytes = file.sizeBytes,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val paths = resolved.values.filter { it.copied }.map { it.path }
            resolved.clear()
            AppScope.io.launch { paths.forEach { runCatching { File(it).delete() } } }
        }
    }

    return items
}

/**
 * One decodable copy of [uri], or null when it is not an image or cannot be read.
 *
 * NULL RATHER THAN AN EXCEPTION, and null rather than a placeholder. A record form's batch legitimately
 * holds audio, video and PDFs, and a picked file whose provider has since revoked the grant is an
 * ordinary Tuesday. Both mean "not a photograph this panel can measure" — which is the same thing the
 * stage surface says by filtering on `mediaType == "IMAGE"` — and neither is worth an error the
 * designer cannot act on.
 */
private fun dwWorkingCopy(context: Context, uri: Uri): DwWorkingFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        )
        ?: return null
    if (!mimeType.startsWith("image/")) return null

    // Already a file. Used where it lies — see the header for why this one is not copied.
    if (uri.scheme == null || uri.scheme == "file") {
        val existing = uri.path?.let { File(it) }?.takeIf { it.isFile } ?: return null
        return DwWorkingFile(path = existing.absolutePath, sizeBytes = existing.length(), copied = false)
    }

    val directory = File(context.cacheDir, WORKING_DIR)
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val target = File(directory, "${UUID.randomUUID()}.img")
    val copied = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
        }
    }.getOrNull()
    // A zero-length copy is a read that failed quietly, and `decodeForDisplay` would answer null on it
    // a moment later with nothing left to say why. Measured off the DISK rather than off the stream's
    // own count, which is `stageMedia`'s reason and holds here too. Cleaned up rather than left for
    // the sweep: a stray file under `measure-working/` has nothing pointing at it.
    if (copied == null || copied <= 0L || target.length() != copied) {
        runCatching { target.delete() }
        return null
    }
    return DwWorkingFile(path = target.absolutePath, sizeBytes = copied, copied = true)
}

/**
 * The units a [DwRecordDimension] may name, exposed so the tie can be asserted rather than believed.
 *
 * Not read by the code above — [dwRecordMeasureTargets] asks `dwMeasurableLengthFields`, which asks
 * `DwPhotoMeasure.LENGTH_UNITS` — and read by `RecordMeasureFieldTest`, which holds every unit in
 * [PRODUCT_MEASURE_DIMENSIONS] and [TOOL_MEASURE_DIMENSIONS] to it. If that assertion ever needs this
 * set widened, the change belongs in `DwPhotoMeasure` and its web authority, never here.
 */
internal val DW_RECORD_UNITS: Set<String> get() = DwPhotoMeasure.LENGTH_UNITS.keys
