package com.designprototype.workshop.ui.designworkshop

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * **"SAVE THIS DRAWING" — the export step of the sketch tracer, on the handset.**
 *
 * ── WHAT THIS CARD IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * A designer has traced a photographed sketch and is looking at the result. This is where the drawing
 * leaves the application: five formats, one button, and then the OS's own share sheet.
 * `DwSketchTraceExport.kt` holds the table and the words; `DwSketchTraceExporter.kt` holds the seam to
 * whatever writes the bytes; `DwSketchTraceExportFile.kt` holds the route to Downloads. This file is
 * only the surface.
 *
 * IT IS NOT THE ATTACH. `DwSketchTracePanel` files the SVG on `sketch.lineArtFile` through the
 * ordinary attach door; this card writes to the DEVICE and to the share sheet and touches no record
 * field at all. Keeping the two apart is what lets a designer take a PDF away without anything
 * reaching the archive — and [DW_TRACE_EXPORT_REPORT_SENTENCE] is printed against every save so that
 * the difference between "on my phone" and "in the report" is never left to be inferred.
 *
 * ── ON THIS BUILD, TWO OF THE FIVE ACTUALLY WRITE, AND THE CARD SAYS SO ───────────────────────
 *
 * The shipped JavaScript bundle exposes six calls and none of them is a writer — see
 * `DwSketchTraceExporter.kt`'s header for the measurement and the re-check command. Two of the five
 * formats need no writer at all:
 *
 *  * the SVG, because the engine's own `svgWriter` already ran inside the isolate on the way out and
 *    its string is in hand; and
 *  * the PNG, because a picture is the PLATFORM's job — `Bitmap.compress` here, `canvas.toBlob` in
 *    the browser, on `exportFormats.ts`'s own rule that the platform layer owns the pixel formats it
 *    already has an encoder for. `DwSketchTraceExportRaster.kt` carries that argument in full.
 *
 * So a host with a `DwTraceExporterUnavailable` behind it still offers the drawing AND a picture of
 * it, and refuses the PDF, the EPS and the DXF in a sentence that names what does work. That was the
 * shipping state while the engine was a JavaScript bundle with no writers in it; this app now mounts
 * [DwTraceKotlinExporter] and writes all five, and the split above is what made the port a one-line
 * change at `rememberDwTraceExporter` rather than a rewrite of this card.
 *
 * ── IT TAKES PRIMITIVES AND NOT A `DwTraceResult`, ON PURPOSE ─────────────────────────────────
 *
 * Everything this card needs from a finished trace it takes one value at a time rather than taking
 * the result object, which keeps this file independent of two data classes other lanes own and are
 * still changing. It also means the card can be composed in a preview, or read by a person, with
 * no runtime wired, which is worth having for a preview even now that every build has an engine. (NO COUNT OF THOSE VALUES IS WRITTEN HERE. The
 * previous one said seven, was accurate on the day, and was wrong by the next parameter — the same
 * failure `FieldRenderer` records for its own "two fields and nothing else" sentence, which has been
 * wrong twice. The parameter list below is the register.)
 *
 * ── ONE BUSY FLAG, HELD BY THE HOST ───────────────────────────────────────────────────────────
 *
 * [busy] and [onBusyChange] are the host screen's flag, not a private one, and that is the same
 * discipline `QuestionnaireHandoffCard` states: building a file ends in `persistFileToDownloads`,
 * which is the same MediaStore write the report export and the two .xlsx downloads make into the same
 * folder, "and two of those racing is how one of them ends up truncated with no error anywhere". It is
 * also the trace panel's own rule from the other direction — `SketchTraceField.tsx:223-231` records
 * what two independent busy flags cost: "the loser would report 'the trace did not finish' while the
 * winner quietly succeeded".
 *
 * ── A PREVIEW IS NEVER SAVED ──────────────────────────────────────────────────────────────────
 *
 * [isPreview] is `DwTraceResult.isPreview` — the trace ran below full resolution. Saving that hands
 * the designer a coarser drawing than the one they approved with nothing on screen to say so, which
 * is the web panel's own fifth property (`SketchTraceField.tsx:64-70`) and the same gate
 * `DwSketchTracePanel` already puts on its Attach button. So the card refuses, names the remedy, and
 * offers [onNeedFullResolution] where the host wired one.
 */

/** The 48dp touch floor, matching the interchange controls this app already ships. */
private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwSketchTraceExportCard(
    repository: WorkshopRepository,
    /**
     * The engine's own SVG string for the trace on screen — `DwTraceResult.svg`, unaltered.
     *
     * Used verbatim for every SVG save; `DwSketchTraceExporter.kt`'s header carries the proof that it
     * is exactly what `exportSvg` would return. Never edited, re-indented or re-printed on this side.
     */
    traceSvg: String,
    /**
     * The traced geometry, or null when the caller has none to give.
     *
     * `DwTraceResult.geometry`, which the runtime fills from `DwTraceDecoded.geometry`. Null is
     * survivable and not an error — the SVG still saves, because the engine wrote it on the way out —
     * but it is no longer free: the PNG is painted from these shapes, so a host with none loses the
     * picture as well as the three vector take-aways and is told so by
     * [DW_TRACE_NO_GEOMETRY_SENTENCE]. That is a different sentence from a bundle with no writers, and
     * `dwTraceExportPlan`'s own note says why the two stopped being one.
     */
    geometry: DwTraceGeometry?,
    /** `DwTraceResult.width`/`height` — the document's own frame, not a bounding box. */
    documentWidth: Int,
    documentHeight: Int,
    /**
     * `DwTraceDecoded.background` — what the document stage ACTUALLY wrote, packed ARGB or null.
     *
     * Read off the finished document rather than off the requested parameters, because auto-detection
     * runs before the first stage and the two can differ (`worker/protocol.ts:139-149`). See
     * [dwTraceExportBackground] for why the export passes this through rather than choosing.
     */
    documentBackground: Int?,
    /** The photograph's name or path. Only the last segment is used, for the file name and the note. */
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    /**
     * `DwTraceResult.frameNote` — the crop clause, or empty when the whole sheet was traced.
     *
     * TWO JOBS, ONE STRING. It is appended to the provenance note written INTO the PDF and the EPS
     * (`dwTraceProvenanceNote`), and it is what tells [dwTraceExportLosses] to say out loud that the
     * three formats with no metadata channel cannot record a crop the designer made. A file that was
     * cropped and says nothing about it is the "skipped work stated on screen" rule with a reviewer
     * at the end of it: the photograph in the record shows a whole sheet, this file shows a corner.
     */
    frameNote: String = "",
    /** `DwTraceResult.isPreview`. A preview is never saved — see the class header. */
    isPreview: Boolean,
    exporter: DwTraceExporter,
    busy: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Where the host wired the "White background" toggle, if it did.
     *
     * `DwSketchTraceParams.kt` relocates that control to `DwTraceTier.EXPORT`, which is this card —
     * but the VALUE is a leaf of the engine's parameter tree and changing it means patching the params
     * and running the pipeline again, which is machinery the panel owns and this file must not
     * duplicate. So the card draws the chips and calls back; the host patches through
     * `DwTraceRuntime.withOverrides` and re-traces. Null draws the current background as a plain fact
     * instead, so a designer always knows what ground their file will have.
     */
    onBackgroundChange: ((white: Boolean) -> Unit)? = null,
    /** Where the host wired "trace it at full size", if it did. Null draws the sentence alone. */
    onNeedFullResolution: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var formatId by remember { mutableStateOf(DW_TRACE_EXPORT_FORMATS.first().id) }
    var working by remember { mutableStateOf(false) }

    /*
      SAVED STATE IS ABOUT THE FILE THAT WAS WRITTEN, AND IS DROPPED WHEN THE SELECTION MOVES.

      A "Saved — Downloads/sheet-line-art.svg" line still on screen after the designer has switched the
      chips to PDF is a confident wrong answer about which file exists: the Share button beneath it
      would hand over the SVG while the chip above it says PDF. Clearing on a change of format is the
      cheap, honest version of that — the file is still in Downloads, and the sentence that said so has
      simply stopped claiming to describe the current selection.
    */
    var saved by remember { mutableStateOf<DwTraceExportSaved?>(null) }

    /** What was true of the file that was just written — the PNG's reduction. Cleared with [saved]. */
    var savedNote by remember { mutableStateOf("") }

    val format = remember(formatId) {
        dwTraceExportFormat(formatId) ?: DW_TRACE_EXPORT_FORMATS.first()
    }

    /*
      TWO CAUSES, TWO SENTENCES, AND THAT IS A CHANGE FROM WHAT THIS FILE USED TO ARGUE.

      It used to fold "this build's bundle has no writers" and "this trace came back with no geometry"
      into one sentence, because they were "different facts inside the app and the SAME fact to the
      designer: the other four formats cannot be written here, and the SVG can". That was true and it
      stopped being true when the PNG left through the platform's own encoder instead of through the
      bundle. A phone with no writers now saves the SVG AND the picture; a trace with no shapes saves
      the SVG alone. Different sets of working controls are different sentences — which is the same
      test `DwSketchChooserSentenceTest` applies from the other side, read the other way round.

      `dwTraceExportPlan` owns both answers so the routing rule stays testable with no exporter, no
      runtime and no device.
    */
    val plan = remember(format, exporter.refusal, geometry) {
        dwTraceExportPlan(format, exporter.refusal, hasGeometry = geometry != null)
    }
    val losses = remember(format, documentBackground, documentWidth, documentHeight, frameNote) {
        dwTraceExportLosses(
            format = format,
            documentBackground = documentBackground,
            documentLongEdgePx = maxOf(documentWidth, documentHeight),
            frameNote = frameNote,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Save this drawing",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            // THE COUNT IS READ, NEVER TYPED. See DW_TRACE_EXPORT_FORMAT_COUNT.
            "$DW_TRACE_EXPORT_FORMAT_COUNT formats. The drawing is the same in all of them; what " +
                "changes is which machine can open it.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DW_TRACE_EXPORT_FORMATS.forEach { row ->
                FilterChip(
                    selected = row.id == formatId,
                    onClick = {
                        if (busy) return@FilterChip
                        formatId = row.id
                        saved = null
                        savedNote = ""
                    },
                    label = { Text(row.label, fontSize = 13.sp) },
                    modifier = Modifier.heightIn48(),
                )
            }
        }

        // ALWAYS-VISIBLE TEXT UNDER THE ROW, never a tooltip: a phone has no hover, and these sentences
        // are the only documentation a designer offline for a fortnight has. Carried verbatim from the
        // web's table so both clients describe one format one way.
        Text(
            format.hint,
            color = MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        HorizontalDivider(color = MaterialTheme.field.hairline)

        /*
          THE BACKGROUND, WHICH IS ONE VALUE WITH ONE AUTHORITY.

          `output.background` is a leaf of the engine's parameter tree and `sanitizeTraceParams` is the
          only thing entitled to say what is legal in it. This card shows it and, where the host wired
          a callback, asks for it to be changed — it never holds a second copy. See
          [dwTraceExportBackground] for why an export-time background of its own would break the SVG
          shortcut and let one drawing leave this phone two ways.
        */
        Text(
            "Background",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (onBackgroundChange != null) {
            val white = dwTraceBackgroundIsWhite(documentBackground)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { option ->
                    FilterChip(
                        selected = option == white,
                        onClick = {
                            if (busy || option == white) return@FilterChip
                            saved = null
                            savedNote = ""
                            onBackgroundChange(option)
                        },
                        label = { Text(if (option) "White" else "Transparent", fontSize = 13.sp) },
                        modifier = Modifier.heightIn48(),
                    )
                }
            }
            Text(
                DW_TRACE_BACKGROUND_RETRACE_SENTENCE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        } else {
            // NO CONTROL WIRED, SO THIS IS A FACT AND NOT A CHOICE. Drawn anyway, because the losses
            // below refer to the ground the file will have and a reader needs to know which it is.
            Text(
                dwTraceBackgroundLabel(documentBackground),
                color = MaterialTheme.field.body,
                fontSize = 12.sp,
            )
        }

        /*
          EVERY LOSS, UNCONDITIONALLY, AND THE REPORT SENTENCE IS ALWAYS THE FIRST OF THEM.

          The same rule the vendored pipeline states about its own notes and this repository takes most
          seriously: "A pipeline that silently discards 4 000 paths and one that found nothing look
          identical on screen otherwise" (`pipeline.ts:46-50`). Here the ambiguity being closed is
          between a drawing that reaches an officer and one that does not.
        */
        losses.forEach { line ->
            Text(
                "· $line",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        when {
            isPreview -> {
                Text(
                    DW_TRACE_EXPORT_PREVIEW_SENTENCE,
                    color = MaterialTheme.field.warning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (onNeedFullResolution != null) {
                    OutlinedButton(
                        onClick = { if (!busy) onNeedFullResolution() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn48(),
                    ) {
                        Text("Trace it at full size", fontSize = 13.sp)
                    }
                }
            }

            plan is DwTraceExportPlan.Refused -> {
                // A SENTENCE AND NOT A DEAD BUTTON, on the rule every refusal in this feature is held
                // to: a dead button teaches a designer the feature is broken; a sentence teaches them
                // what to do.
                // The SVG chip beside this one still works, which is the remedy the sentence names.
                Text(
                    plan.reason,
                    color = MaterialTheme.field.warning,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }

            else -> {
                OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        onBusyChange(true)
                        working = true
                        saved = null
                        savedNote = ""
                        scope.launch {
                            runCatching {
                                dwWriteTraceExport(
                                    context = context,
                                    repository = repository,
                                    exporter = exporter,
                                    plan = plan,
                                    format = format,
                                    geometry = geometry,
                                    documentWidth = documentWidth,
                                    documentHeight = documentHeight,
                                    documentBackground = documentBackground,
                                    traceSvg = traceSvg,
                                    sourceName = sourceName,
                                    shapeCount = shapeCount,
                                    nodeCount = nodeCount,
                                    frameNote = frameNote,
                                )
                            }
                                .onSuccess { outcome ->
                                    when (outcome) {
                                        is DwTraceWriteOutcome.Saved -> {
                                            saved = outcome.saved
                                            savedNote = outcome.note
                                        }
                                        is DwTraceWriteOutcome.Refused -> onError(outcome.reason)
                                    }
                                }
                                .onFailure { error ->
                                    // A CANCELLED JOB IS NOT A FAILURE. `trace.worker.ts:156-157`
                                    // states it for the web — a cancel "must never reach the user as
                                    // one" — and the same holds when a composition leaves while a file
                                    // is being written.
                                    if (error !is CancellationException) {
                                        onError(
                                            error.message?.takeIf { it.isNotBlank() }
                                                ?: "That file could not be saved."
                                        )
                                    }
                                }
                            working = false
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn48(),
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) "Writing the file…" else format.save, fontSize = 13.sp)
                }
            }
        }

        /*
          A POLITE LIVE REGION, because this block APPEARS IN ANSWER TO A PRESS. A reader who cannot see
          the layout gets nothing at all from a panel that quietly materialises below the button they
          just activated — the same construction `DwDocumentPreview` uses for its open-failed line, and
          for the same reason. The box is drawn whether or not there is anything in it, so the region is
          stable across the change, which is what makes the announcement fire.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            saved?.let { file ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Saved",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // WHERE IT WENT, IN THE NAME MediaProvider ACTUALLY USED. A colliding DISPLAY_NAME
                    // is silently uniquified to `name (1).ext`, and printing the requested name would
                    // name a file that is not on disk — the defect `persistFileToDownloads` re-queries
                    // the store to close.
                    Text(file.savedTo, color = MaterialTheme.field.body, fontSize = 11.sp)

                    // WHAT WAS DECIDED WHILE THIS FILE WAS BEING WRITTEN, in the past tense and with
                    // the real numbers in it. Inside the live region above rather than beside it, so a
                    // reader who cannot see the layout is told the picture was reduced in the same
                    // announcement that tells them it was saved — the reduction is a property of the
                    // file that has just appeared, not a standing caveat about the format.
                    if (savedNote.isNotBlank()) {
                        Text(
                            savedNote,
                            color = MaterialTheme.field.warning,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }

                    if (file.shareUri != null) {
                        OutlinedButton(
                            onClick = {
                                val send = dwTraceExportShareIntent(file) ?: return@OutlinedButton
                                // `runCatching`: a handset with no app willing to receive this type
                                // throws ActivityNotFoundException, which must not take a stage down
                                // over a button somebody pressed out of curiosity.
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(send, "Send the drawing")
                                    )
                                }.onFailure {
                                    onError(
                                        "Nothing installed on this device offered to send it. It is " +
                                            "still saved — ${file.savedTo}"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn48(),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send it")
                        }
                        Text(
                            DW_TRACE_EXPORT_SHARE_CAVEAT,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    } else {
                        Text(
                            DW_TRACE_EXPORT_NO_SHARE_SENTENCE,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The press, as a function
 * ──────────────────────────────────────────────────────────────────────────── */

/** Either a file on the flash, or a sentence saying why not. */
sealed class DwTraceWriteOutcome {
    /**
     * @param note what is true of THIS file and was decided while it was being written, or empty.
     *
     * The only thing that fills it today is [dwTracePngReductionNote] — a picture capped at
     * [DW_TRACE_PNG_MAX_EDGE_PX] is smaller than the drawing it came from, and how much smaller is
     * not known until the size has been computed. The web carries the identical note out of
     * `exportPngFile` beside the file for the same reason: a cap stated in the future tense beside a
     * format chooser is advice, and the same cap stated in the past tense with two real numbers in it
     * is a fact about the file now in the designer's Downloads folder.
     */
    class Saved(val saved: DwTraceExportSaved, val note: String = "") : DwTraceWriteOutcome()

    class Refused(val reason: String) : DwTraceWriteOutcome()
}

/**
 * Produce the bytes for one save and put them in Downloads.
 *
 * OUT OF THE BUTTON'S LAMBDA ON PURPOSE. What a press does is: pick the route, get bytes, name the
 * file, write it. Four steps that have to happen in that order and that a reader should be able to see
 * in one place — a composable's `onClick` is where a sequence like this becomes six nested lambdas
 * nobody re-reads.
 *
 * THE SVG ROUTE IS THE ONLY PLACE THIS SIDE PRODUCES BYTES ITSELF, and it produces them by encoding a
 * string the engine wrote. `DwSketchTraceExporter.kt`'s header carries the proof that the string is
 * exactly `exportSvg`'s answer for the document's own background, and the warning about what would
 * invalidate that proof.
 *
 * `Charsets.UTF_8` and nothing else: the engine's `encodeUtf8` is a `TextEncoder`, the SVG declares
 * `encoding="UTF-8"` in its own XML declaration, and a platform default charset here would produce a
 * file whose bytes disagree with its own header on any handset whose locale is not UTF-8.
 */
private suspend fun dwWriteTraceExport(
    context: android.content.Context,
    repository: WorkshopRepository,
    exporter: DwTraceExporter,
    plan: DwTraceExportPlan,
    format: DwTraceExportFormat,
    geometry: DwTraceGeometry?,
    documentWidth: Int,
    documentHeight: Int,
    documentBackground: Int?,
    traceSvg: String,
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String,
): DwTraceWriteOutcome {
    var note = ""
    val bytes: ByteArray = when (plan) {
        is DwTraceExportPlan.Refused -> return DwTraceWriteOutcome.Refused(plan.reason)

        is DwTraceExportPlan.FromTraceSvg -> traceSvg.toByteArray(Charsets.UTF_8)

        is DwTraceExportPlan.FromPlatformRaster -> {
            // THIS DEVICE PAINTS IT AND THIS DEVICE'S OWN ENCODER WRITES IT — no bundle, no isolate,
            // no marshalling pass. `DwSketchTraceExportRaster.kt` carries the argument for why a
            // picture is the platform's job on both clients, and moves itself off the main thread.
            val handle = geometry
                ?: return DwTraceWriteOutcome.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
            // THE SIZE IS COMPUTED HERE AS WELL AS INSIDE THE RENDERER, AND THEY CANNOT DISAGREE
            // BECAUSE IT IS ONE FUNCTION OVER ONE PAIR OF NUMBERS. The alternative — the renderer
            // handing its size back — would put a second return value on a function whose answer is
            // bytes, to save a call that is three integer operations.
            note = dwTracePngReductionNote(
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                size = dwTracePngSize(documentWidth, documentHeight),
            )
            dwTraceRenderPngBytes(
                geometry = handle,
                documentWidth = documentWidth,
                documentHeight = documentHeight,
                // PASSED THROUGH, NEVER CHOSEN — the same rule and the same function the vector arm
                // below uses, which is what stops a PNG and a PDF of one drawing disagreeing about
                // their ground. `renderTrace`'s own default is the COMPARATOR's white and is wrong
                // for a file, so this argument is never omitted.
                background = dwTraceExportBackground(documentBackground),
            ) ?: return DwTraceWriteOutcome.Refused(DW_TRACE_PNG_MEMORY_REFUSAL)
        }

        is DwTraceExportPlan.FromExporter -> {
            // Unreachable through the card, which routes to Refused when the geometry is missing. Kept
            // as an answer rather than a throw, because a caller holding a stale plan crashing a stage
            // is worse than a sentence — the disposition `exportVectorFile` takes for an id it does not
            // recognise. It answers the MISSING-SHAPES sentence and not the missing-writers one: the
            // two used to be one string and are now two facts with two remedies, and this branch is
            // reached by exactly the first of them.
            val handle = geometry
                ?: return DwTraceWriteOutcome.Refused(DW_TRACE_NO_GEOMETRY_SENTENCE)
            val outcome = exporter.export(
                DwTraceExportRequest(
                    geometry = handle,
                    width = documentWidth,
                    height = documentHeight,
                    format = format,
                    // PASSED THROUGH, NEVER CHOSEN. See dwTraceExportBackground for what null would
                    // do differently to the vector writers and to the rasteriser.
                    background = dwTraceExportBackground(documentBackground),
                    // THE FRAME CLAUSE REACHES THE FILE HERE, AND THIS IS THE LAST HOP OF A CHAIN
                    // THAT WAS OPEN FOR THE WHOLE LIFE OF THE PARAMETER. `dwTraceProvenanceNote`
                    // declared `frameNote` and documented that the handset had no crop to describe;
                    // `DwTraceFramePanel` closed that, `dwTraceCropNote` builds the clause in
                    // `imageEdit.describeEdit`'s exact words, and `DwTraceResult.frameNote` carries
                    // it here. Until this argument existed the channel was a capability nothing read.
                    provenanceNote = dwTraceProvenanceNote(
                        sourceName = sourceName,
                        shapeCount = shapeCount,
                        nodeCount = nodeCount,
                        frameNote = frameNote,
                    ),
                )
            )
            when (outcome) {
                is DwTraceExportOutcome.Refused -> return DwTraceWriteOutcome.Refused(outcome.reason)
                is DwTraceExportOutcome.Done -> outcome.bytes
            }
        }
    }

    val name = dwTraceExportFileName(
        sourceName = sourceName,
        extension = format.extension,
        suffix = dwTraceSaveSuffix(format),
    )
    return DwTraceWriteOutcome.Saved(
        dwSaveTraceExport(
            context = context,
            repository = repository,
            bytes = bytes,
            fileName = name,
            mime = format.mime,
        ),
        note = note,
    )
}
