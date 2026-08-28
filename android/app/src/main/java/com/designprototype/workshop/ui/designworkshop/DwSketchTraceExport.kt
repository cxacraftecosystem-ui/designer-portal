package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Immutable

/**
 * **WHAT A TRACED SKETCH CAN BE WRITTEN OUT AS, AND WHAT THAT FILE DOES AND DOES NOT REACH.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * Stage 11 lets a designer photograph a paper sketch and trace it into vector line art. The vendored
 * engine already has the writers — `frontend/lib/trace/engine/svgWriter.ts`, `dxfWriter.ts`,
 * `epsWriter.ts`, `pdfWriter.ts`, `pngEncoder.ts`, all dispatched by `exportFormats.exportDocument` —
 * so the handset gets every one of them for free through whatever runtime runs that engine. **What
 * needed writing down is which of them a designer is offered, what each one loses, and where the file
 * goes.** That is this file, its platform half in `DwSketchTraceExportFile.kt`, the raster in
 * `DwSketchTraceExportRaster.kt`, and the card in `DwSketchTraceExportCard.kt`.
 *
 * **ONE OF THE FIVE IS NOT THE ENGINE'S TO WRITE, ON BOTH CLIENTS.** The PNG is a picture, and every
 * platform this app runs on already has a PNG encoder that is better tested than any bundled one
 * could be. The portal's own table records the decision from its side — its PNG is "written by
 * `canvas.toBlob` rather than by the engine's own PNG encoder", because "`exportFormats.ts` states in
 * its own header that the platform layer owns the pixel formats the browser already has an encoder
 * for". This handset makes the same call with `android.graphics` and `Bitmap.compress`, which is why
 * a build whose bundle carries no writers at all still offers two of the five formats rather than
 * one. [dwTracePngSize] holds the one rule the two platforms must agree on.
 *
 * PURE. No `Context`, no Compose surface, no `android.graphics`, no clock, no randomness — the same
 * split `data/DwSketchPlate.kt` states for its own feature, and for its reason rather than for
 * tidiness: there is no Robolectric in this module (`app/build.gradle.kts` declares JUnit 4 and
 * nothing else), so anything touching the framework is by construction code no unit test can reach.
 * Everything here is pinned by `DwSketchTraceExportTest`.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE SENTENCE THAT MATTERS MOST ON THIS SCREEN, AND IT IS ABOUT THE REPORT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **A vector export is something the designer TAKES AWAY. It does not reach the officer.** Every
 * format below writes a file to this phone's Downloads folder and to the share sheet, and NONE of
 * them puts anything into the .docx or the .pdf a ministry receives. Established on 2026-08-27 by
 * reading the three modules that are the authority on it, not by reading a help string:
 *
 *  * `backend/app/services/report_builder.py` — `_images` is the only placement path there is and it
 *    filters on `FieldType.IMAGE` and `FieldType.IMAGE_LIST`. `format_value` prints `""` for those two
 *    (they are placed as pictures) and, for every other media type, the field's own line — "1 document
 *    attached" — and nothing else.
 *  * `backend/app/services/report_templates.py` — `SpecialSection.ANNEXURE_MEDIA` is **photographs
 *    only**, and its comment says so outright: "``_render_media_annexure`` gathers through
 *    ``ReportBuilder._images``, which filters on IMAGE and IMAGE_LIST, so the seventeen FILE, AUDIO and
 *    VIDEO fields the registry declares reach no contact sheet and cannot". A FILE annexure is recorded
 *    there as a REFUSAL with two reasons, not as an omission.
 *  * `backend/app/services/report_annexures.py` — the only annexure carrying non-photographic material
 *    carries WORDS: transcripts. There is no sibling for files.
 *
 * `sketch.lineArtFile` is `"type": "FILE"` in the bundled registry
 * (`android/app/src/main/assets/design-workshop-schema.json`, stage 11, `sketch` entity, help text "An
 * SVG or vector export, if one was produced"). So even the SVG that IS filed on the record is named in
 * the report and not carried into it — which `ReportBuilder.attachments_named_but_not_carried` counts
 * and states beside the download, and which stays true whatever this screen writes.
 *
 * Re-check both halves of that, 2026-08-27:
 *
 *     grep -n "_image_sources\|_render_media_annexure" backend/app/services/report_builder.py
 *     grep -n "ANNEXURE_MEDIA" backend/app/services/report_templates.py
 *
 * ── WHY THAT PARAGRAPH IS HERE AND NOT LEFT TO WHOEVER WRITES THE COPY ────────────────────────
 *
 * Because this repository has a standing warning that **a claim about what a report CONTAINS cannot be
 * verified from the surface you are writing on**, and it names the price. From
 * `ReportBuilder.attachments_named_but_not_carried`: "both the web form and the handset render their
 * help straight off the published registry, so the same wrong sentence can be written in
 * ``stage_definitions`` any number of times without a single surface disagreeing with it." The count
 * it gives is five passes over one claim in a day: `report_annexures` and `report_custom_sections`
 * both open by recording "three surfaces told the designer the office's copy would carry it";
 * `stage_definitions` records at `surveyDocument` that the identical false claim was made three times
 * in one wave; and correcting the two sentences in `report_builder` itself was "the fourth pass over
 * the same claim in a day". Its instruction to the next reader is *cite the shape, not the instance* —
 * so the three module names above are the citation, and [DW_TRACE_EXPORT_REPORT_SENTENCE] is the one
 * string every surface in this feature prints, rather than five surfaces each phrasing it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE TABLE IS THE WEB'S TABLE
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [DW_TRACE_EXPORT_FORMATS] mirrors `frontend/components/sketches/upload/traceExport.ts`'s
 * `EXPORT_FORMATS` row for row — same ids, same order, same extensions, same MIME types, same
 * `attachable` flags, and the hints carried across rather than re-written. That is not deference; it
 * is the same rule the vendored engine is under. One sheet of paper traced on two clients must produce
 * one drawing, and a designer who saved a DXF on a laptop and cannot find it on the handset has been
 * given two different products. Verified against that file on 2026-08-27; re-check with
 *
 *     grep -c "^    id: \"" frontend/components/sketches/upload/traceExport.ts
 *
 * and compare against [DW_TRACE_EXPORT_FORMAT_COUNT].
 *
 * [DW_TRACE_NOT_OFFERED] is the other half and it is a strange thing to ship on purpose. The web's own
 * header explains why it exists: three finished writers sat in the engine unreachable from any control
 * for as long as the feature existed, "and nothing anywhere said whether that was a decision or an
 * oversight — which is precisely why it read as an oversight to the audit that found it". Ten formats
 * exist in `ExportFormat`; five are offered and five are refused in writing.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * TWO TRAPS IN THE ENGINE'S EXPORT OPTIONS THAT A HANDSET CARD WILL OTHERWISE WALK INTO
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **1. `background = null` MEANS TWO DIFFERENT THINGS IN THE TWO ARMS OF ONE DISPATCHER.** Read
 * `engine/exportFormats.ts`: the vector arm goes through `prepareVectorDoc`, which is
 * `o.background === null ? scaled : new VecDocument(..., o.background)` — so null means *leave the
 * document's own background alone*. The raster arm calls `render(doc, w, h, o.background ?? 0)`, and
 * `raster.render` ignores `doc.background` entirely and clears with the argument — so null means
 * *transparent*. For a document traced with `output.background = null` (the engine's default, and the
 * only spelling of transparent, `params.ts:359`) the two coincide. For a document traced with white
 * they do not, and one press of one control would then produce a white PDF beside a transparent PNG.
 *
 * The rule that makes them agree by construction, and it is what [dwTraceExportBackground] encodes:
 * **an exporter passes the DOCUMENT'S OWN background through, always, and never null.** Then a
 * transparent document keeps null on the vector side and becomes `null ?? 0` on the raster side —
 * transparent both ways — and a white document is rebuilt with the white it already had and cleared to
 * that same white. Every value agrees across both arms with no special case. The long form is in the
 * section above [dwTraceExportBackground].
 *
 * **2. THE FILES CARRY THE UPSTREAM TRACER'S NAME, AND NO OPTION REMOVES IT WITHOUT ALSO REMOVING THE
 * PROVENANCE NOTE.** With `includeMetadata` on, `svgWriter.write` emits
 * `<title>Offline Tracer export</title>` and `<desc>Generated by Offline Tracer. All processing
 * performed on device.</desc>`; `pdfWriter.writePdf` emits `/Producer (Offline Tracer) /Creator
 * (Offline Tracer)`; `epsWriter.writeEps` emits `%%Creator: Offline Tracer`. The `title` argument
 * moves `/Title` and `%%Title` and reaches none of those three. With `includeMetadata` off all of it
 * goes — and so does the sentence saying what made the drawing and from what.
 *
 * **This build carries the metadata and says so**, for three reasons stated here so nobody re-opens it
 * as a bug. It is TRUE — the drawing was made by the vendored Offline Tracer engine, on the device,
 * with the owner's permission, and a `/Producer` naming the library that wrote a file is what every
 * PDF in the world carries. Stripping it means either editing a vendored writer, which is an owner
 * decision and not ours (`frontend/lib/trace/README.md`), or writing a second SVG writer on this side,
 * which is the divergence this whole feature is disciplined against. And the web already ships it:
 * `traceExport.exportVectorFile` passes `includeMetadata: true` with the provenance note as the title.
 * What must not happen is a designer meeting it for the first time in an officer's inbox, so
 * [dwTraceExportLosses] states it beside the file.
 *
 * ── THE ASYMMETRY THIS OPENS, STATED PRECISELY BECAUSE IT IS EASY TO STATE TOO WEAKLY ─────────
 *
 * **The two clients write SVGs with two different writers**, and that is a bigger difference than the
 * branding. The web builds `sketch.lineArtFile` with its own
 * `frontend/components/sketches/upload/geometryToSvg.buildSvg`, which walks the flat arrays on the
 * page rather than pulling `engine/path.ts` into the bundle; the handset saves the engine's own
 * `svgWriter.write` string, which is what the cross-runtime parity harness compares exactly. So:
 *
 *  * only the web's copy carries the provenance note (an XML comment), and only the handset's carries
 *    the engine's `<title>`/`<desc>`; and
 *  * the PATH DATA itself is spelled differently. The parity lane measured it on 2026-08-27:
 *    `buildSvg` emits `M7.25 1 C7.83 1.08 …` where `SvgWriter` emits `M7.25 1C7.83 1.08 …` — a space
 *    after the command letter, an explicit `C` on every cubic where `toD` elides it for a run, and a
 *    space before `Z`. `engine/svgPathData.ts:35-39` measures those elisions at "roughly one
 *    character in eight of the largest thing this app writes".
 *
 * **The NUMBERS are held identical and the STRINGS are not.** Saying "the two clients' SVGs differ in
 * their metadata" would be the weak version of this and would let somebody conclude the files are
 * otherwise interchangeable; they render the same drawing and they do not diff cleanly. That is a
 * real disagreement between two surfaces, it is the owner's to settle — it is in this lane's
 * followups — and it is recorded here rather than quietly patched on one side.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Sizes — declared above the table, because the table quotes them
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The longest edge a rendered PNG may have.
 *
 * The web's own number and its own argument (`traceExport.PNG_MAX_EDGE_PX`): a trace of a 4096px
 * photograph would otherwise produce a 4096px canvas, which is 67 MB of RGBA before compression, and
 * 2048 is still about 17 cm across printed at 300 dpi with four vector formats beside it that have no
 * ceiling at all.
 *
 * IT BITES HARDER HERE THAN IT DOES THERE. On the web an oversized raster is a `toBlob` that returns
 * null; on a 5.9 GB Galaxy M32 already holding Compose, a camera preview and a stage it is an
 * OutOfMemoryError, and `comparisonPlates.ts:55-57` already names three copies of one big buffer as
 * how a 2 GB handset dies. So this is not a courtesy cap.
 *
 * DECLARED ABOVE THE TABLE BECAUSE THE TABLE QUOTES IT — the PNG row's hint interpolates this constant
 * rather than repeating the digits, so the number a designer reads and the number [dwTracePngScale]
 * enforces cannot drift apart.
 */
const val DW_TRACE_PNG_MAX_EDGE_PX: Int = 2048

/**
 * How far a PNG of this document must be shrunk to sit inside [DW_TRACE_PNG_MAX_EDGE_PX].
 *
 * A CEILING, NEVER AN ENLARGEMENT. The answer is 1.0 for any document whose long edge already fits,
 * because upscaling line art adds no information and multiplies the buffer the note above says a
 * 2 GB handset dies on. Above the cap it is cap-over-long-edge, so the long edge lands exactly on
 * the cap and the aspect ratio is untouched.
 *
 * LONG EDGE, NOT WIDTH. A portrait sheet is capped on its height; a landscape one on its width. The
 * cap is a statement about the biggest buffer that may be allocated, and the biggest buffer is set
 * by whichever edge is longer.
 *
 * THE DEGENERATE CASE IS NOT HYPOTHETICAL, WHICH IS WHY IT IS NOT AN EXCEPTION. A non-positive
 * dimension reaches these writers — it is the reason `svgWriter.sanitizeDimension` exists — and a
 * division by zero here would be a crash in the middle of an unsaved stage. A non-positive edge is
 * therefore read as 1, which lands on 1.0 by the ordinary rule rather than by a special case.
 *
 * THE SAME ARITHMETIC AS THE WEB, DELIBERATELY. `traceExport.exportPngFile` computes
 * `Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight))` over the same two coercions, so one
 * drawing exported from a handset and from a laptop is the same number of pixels across.
 *
 * IT HAS A PRODUCTION CALLER NOW, AND THE PARAGRAPH THAT SAID OTHERWISE WAS RIGHT WHEN IT WAS
 * WRITTEN. It said the only implementation of [DwTraceExporter] in this bundle refuses every
 * raster format, so nothing rasterised on a handset — true of a PNG asked of the vendored
 * writers, and it stopped being the whole story the moment the PNG stopped going through them.
 * [dwTraceRenderPngBytes] paints the same geometry with `android.graphics` and encodes it with
 * `Bitmap.compress`, which is the platform's own PNG encoder and needs no bundle at all. That is
 * the web's own arrangement rather than a divergence: its table records that its PNG is "written
 * by `canvas.toBlob` rather than by the engine's own PNG encoder — `exportFormats.ts` states in
 * its own header that the platform layer owns the pixel formats the browser already has an
 * encoder for". Two platforms, two platform encoders, one cap and one scale rule.
 *
 * [dwTracePngSize] is what a caller usually wants: this ratio applied to both edges, rounded the
 * way the web rounds it. Nothing passes this to `ExportOptions.scale` today, and whoever wires the
 * vendored writers still should — `DwTraceExportRequest`'s own note says the scale is derived,
 * never chosen. Re-check the callers with
 * `grep -rn "dwTracePngScale(" android/app/src/main/java`. On 2026-08-27 it answers THREE times:
 * the declaration below, this line (which the pattern matches itself), and the one real call
 * inside [dwTracePngSize]. The trailing `(` keeps the count honest — it excludes the
 * `[dwTracePngScale]` KDoc links in `DwSketchTraceExporter.kt` and on the constant above, which a
 * bare name pattern counts as hits.
 */
fun dwTracePngScale(width: Int, height: Int): Double {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val longEdge = if (safeWidth > safeHeight) safeWidth else safeHeight
    return (DW_TRACE_PNG_MAX_EDGE_PX.toDouble() / longEdge.toDouble()).coerceAtMost(1.0)
}

/** The pixel size a rendered PNG of one document comes out at. See [dwTracePngSize]. */
@Immutable
data class DwTracePngSize(
    val width: Int,
    val height: Int,
    /** True when [DW_TRACE_PNG_MAX_EDGE_PX] actually bit, i.e. the picture is smaller than the drawing. */
    val reduced: Boolean,
)

/**
 * The bitmap a rendered PNG of a [width] x [height] document is allocated at.
 *
 * **THE SAME THREE LINES AS `traceExport.exportPngFile`, IN THE SAME ORDER**, because the two
 * clients must hand one drawing to one printer at one size:
 *
 *     const scale  = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
 *     const width  = Math.max(1, Math.round(sourceWidth  * scale));
 *     const height = Math.max(1, Math.round(sourceHeight * scale));
 *
 * `Math.round` and `kotlin.math.roundToInt` are the same rule for the values that reach here — both
 * round half AWAY from zero for a positive number — and every value that reaches here is positive
 * because [dwTracePngScale] coerces a non-positive edge to 1 before dividing. That coincidence is
 * worth naming rather than relying on: the two languages differ at NEGATIVE halves (JavaScript
 * rounds -0.5 to -0, Kotlin to -1), and a dimension is never one.
 *
 * THE FLOOR OF ONE PIXEL IS NOT DECORATION. A 4096x3 document scales its short edge to 1.5, and a
 * `Bitmap.createBitmap` of height 0 throws `IllegalArgumentException` — inside a save, on a stage
 * with unsaved work. The web's `Math.max(1, …)` is guarding the identical thing one runtime over.
 *
 * [reduced] is what the success sentence is conditioned on, and it is computed HERE rather than by
 * a caller comparing sizes, so the number a designer reads and the bitmap that was allocated can
 * never come from two different pieces of arithmetic.
 */
fun dwTracePngSize(width: Int, height: Int): DwTracePngSize {
    val safeWidth = if (width > 0) width else 1
    val safeHeight = if (height > 0) height else 1
    val scale = dwTracePngScale(safeWidth, safeHeight)
    val outWidth = Math.round(safeWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = Math.round(safeHeight * scale).toInt().coerceAtLeast(1)
    return DwTracePngSize(outWidth, outHeight, reduced = scale < 1.0)
}

/**
 * What is said after a PNG has been written and the cap bit, or empty when it did not.
 *
 * **THE WEB'S SENTENCE, IN THE HANDSET'S OWN VERB.** `exportPngFile` returns this note beside the
 * file and `SketchTraceField` prints it under the download; the only word changed is the one this
 * whole file changes — nothing is downloaded on a phone, the bytes are made here and written to
 * this device's own Downloads folder, which is why [DwTraceExportFormat.save] says "Save".
 *
 * SAID AFTER THE FACT AND NOT ONLY BEFORE IT. [dwTraceExportLosses] already warns that the cap
 * exists, in the future tense, for a designer choosing a format. This is the past tense, with the
 * two real numbers in it, for the designer who now has a file: a picture 2048 px across of a
 * drawing that was 4096 is a reduction they can act on — by saving the SVG beside it — and a
 * reduction nothing in the file itself records.
 */
fun dwTracePngReductionNote(
    documentWidth: Int,
    documentHeight: Int,
    size: DwTracePngSize,
): String {
    if (!size.reduced) return ""
    return "The picture is ${size.width}x${size.height}, reduced from " +
        "${documentWidth}x$documentHeight so it stays inside what a phone can hold. The SVG " +
        "beside it has no such limit."
}

/**
 * What is said when a phone could not allocate the bitmap a PNG of this drawing needs.
 *
 * A SENTENCE AND NOT A CRASH, which is `DwSketchPlate.bitmapOf`'s settled disposition for every
 * large allocation in this app: a plate that could not be built is a sentence on screen and never a
 * crash in the middle of an unsaved stage. The arithmetic behind it is worth stating because it is
 * the largest single allocation this feature makes — a PNG at the cap is
 * 2048 x 2048 x 4 = 16,777,216 bytes of ARGB_8888, twice either display plate.
 *
 * IT NAMES TWO REMEDIES AND BOTH ARE REAL. The SVG is the same drawing and is already in hand, so
 * it costs no allocation at all; and the picture's size follows the DOCUMENT's size rather than the
 * trace resolution, so the control that actually shrinks it is the frame — which is named by the
 * label `DwTraceFramePanel` puts on itself, so a designer is sent to a heading they can see.
 */
const val DW_TRACE_PNG_MEMORY_REFUSAL: String =
    "This phone could not make room for a picture that size. The SVG is the same drawing and needs " +
        "almost no room — or choose a smaller region under “The part of the photograph to trace” " +
        "and trace again, because the picture is as big as the part of the sheet you traced."

/**
 * The dots-per-inch written into a PNG's `pHYs` chunk.
 *
 * A REFUSAL DRESSED AS A NUMBER. `ExportOptions.dpi` is clamped to 1..2400 with a default of 300, and
 * `pngEncoder.encode` writes the chunk for any non-zero value — so the engine gives no way to decline
 * the claim, and every PNG it writes asserts a physical size. The document's units are the
 * photograph's own pixels; nobody in this flow has said how big the sheet was. 300 would assert that a
 * 2048px plate is 6.8 inches across, which nobody measured.
 *
 * 72 is the value that makes a reader show the image at its pixel size, and it is the engine's own
 * choice for the two formats where it had a free hand: `exportFormats.encodeBmp` writes 2835 pixels
 * per metre (72 dpi to the nearest integer) and `encodeTiff` writes 72/1 into both resolution
 * rationals. Matching them keeps one answer to one question inside one module.
 *
 * If a real measurement ever exists — `DwPhotoMeasure` exists and stage 11 has a photograph — this is
 * the constant to replace with it, and the claim would then be true.
 *
 * ── IT DOES NOT REACH THE PNG THIS APP ACTUALLY WRITES TODAY, WHICH IS THE BETTER OUTCOME ─────
 *
 * [dwTraceRenderPngBytes] goes through `Bitmap.compress`, and that encoder takes no dpi argument
 * and is given none here — this side writes no `pHYs` chunk of its own. So a PNG saved from this
 * handset asserts NO physical size, which is what this constant was chosen to approximate in the
 * first place, arrived at by declining to make the claim rather than by making a modest one. The
 * constant stays declared because it is still the right answer for `ExportOptions.dpi` on the day
 * somebody wires the vendored `pngEncoder`, which writes the chunk for any non-zero value and
 * therefore offers no way to decline.
 */
const val DW_TRACE_PNG_DPI: Int = 72

/* ────────────────────────────────────────────────────────────────────────────
 * The formats
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the export table.
 *
 * [engineFormat] names the member of `engine/exportFormats.ExportFormat` this row corresponds to, as a
 * plain string. A string and not a Kotlin enum, for the same reason the web keeps it a string: the
 * enum lives inside the vendored engine, on the far side of whatever runtime runs it, and a second
 * Kotlin declaration of its ten members would be a second register of somebody else's list — the
 * failure this repository has shipped twice. The runtime maps this string; nothing here does.
 */
@Immutable
data class DwTraceExportFormat(
    val id: String,
    /** The word on the chip. */
    val label: String,
    /** The `ExportFormat` member. See the class note. */
    val engineFormat: String,
    /** Without the dot. Checked against `ExportOptions.extension` by the web's own spec. */
    val extension: String,
    val mime: String,
    /**
     * Whether this form may be FILED on `sketch.lineArtFile`.
     *
     * CARRIED HERE, ACTED ON ELSEWHERE. The attach door is the trace panel's, not this file's — see
     * `DwSketchTraceEngine.kt` and this lane's followups. It lives in the table because the table is
     * the one register of what each format is for, and a second list of "the attachable ones" is the
     * pattern `.claude/skills/field-repo-frontend/SKILL.md` §16 records going stale for months.
     */
    val attachable: Boolean,
    /** True for SVG, PDF, EPS and DXF. Mirrors `ExportOptions.isVector`. */
    val isVector: Boolean,
    /**
     * The words on this format's button.
     *
     * "Save" AND NOT "DOWNLOAD", which is the one place the wording deliberately leaves the web's.
     * Downloading is what a browser does; here nothing is fetched — the bytes are produced on this
     * handset and written into its own Downloads folder, exactly as `ReportExport` and the `.dpwq`
     * handoff do, and `ReportScreen` already says "Saved" over that folder. Calling it a download on
     * a phone with no signal would be the wrong verb for the one condition this product is built for.
     */
    val save: String,
    /**
     * The sentence under the row, verbatim from the web's table.
     *
     * ALWAYS-VISIBLE TEXT, NEVER A TOOLTIP. A phone has no hover, and these hints are the only
     * documentation a designer offline for a fortnight has.
     */
    val hint: String,
)

/**
 * The five formats offered, in the order the card lists them.
 *
 * SVG LEADS because it is what `sketch.lineArtFile` is declared for and the only form that can still
 * be edited, re-scaled or sent to a plotter afterwards. The two attachable rows come first so that a
 * chooser above and the buttons below list them in one order.
 */
val DW_TRACE_EXPORT_FORMATS: List<DwTraceExportFormat> = listOf(
    DwTraceExportFormat(
        id = "svg",
        label = "SVG",
        engineFormat = "SVG",
        extension = "svg",
        mime = "image/svg+xml",
        attachable = true,
        isVector = true,
        save = "Save the trace (SVG)",
        hint = "The traced paths themselves. Scales to any size without ever going blocky, opens in " +
            "Illustrator, Inkscape and CorelDRAW, and is what the “Line art” field is " +
            "declared for.",
    ),
    DwTraceExportFormat(
        id = "png",
        label = "PNG",
        engineFormat = "PNG",
        extension = "png",
        mime = "image/png",
        attachable = true,
        isVector = false,
        save = "Save the rendered image (PNG)",
        // "2048px" AND NOT "2048 px": the web's row interpolates the same constant with no space
        // (`traceExport.ts:165`), and a lane whose rule is that one format is described one way on
        // both clients cannot spell one number two ways. The Kotlin interpolation put the space in;
        // `${…}` braces are what take it back out.
        hint = "The drawing rendered as a picture, transparent wherever the drawing is not, up to " +
            "${DW_TRACE_PNG_MAX_EDGE_PX}px on its long edge. Opens anywhere and drops straight into " +
            "a letter or a slide, but it is pixels — enlarge it and it goes soft.",
    ),
    DwTraceExportFormat(
        id = "pdf",
        label = "PDF",
        engineFormat = "PDF",
        extension = "pdf",
        mime = "application/pdf",
        attachable = false,
        isVector = true,
        save = "Save a PDF to send on",
        hint = "Vector, and it opens on every machine you could mail it to without anybody installing " +
            "anything. The one to attach to an email, or to hand to somebody who only needs to look " +
            "at it.",
    ),
    DwTraceExportFormat(
        id = "dxf",
        label = "DXF",
        engineFormat = "DXF",
        extension = "dxf",
        mime = "image/vnd.dxf",
        attachable = false,
        isVector = true,
        save = "Save for a CAD or cutting machine (DXF)",
        hint = "The outlines as CAD geometry, for a laser cutter, a CNC router or a drafting package. " +
            "It is DXF R12, which every controller reads: curves arrive as many short straight lines, " +
            "and colour, fill and line thickness are not carried at all.",
    ),
    DwTraceExportFormat(
        id = "eps",
        label = "EPS",
        engineFormat = "EPS",
        extension = "eps",
        mime = "application/postscript",
        attachable = false,
        isVector = true,
        save = "Save for a print shop (EPS)",
        hint = "Vector PostScript, for a print shop or sign-cutting software that will not take an " +
            "SVG. PostScript has no transparency, so anything part-see-through is flattened onto the " +
            "background as the file is written.",
    ),
)

/**
 * How many formats this handset offers.
 *
 * ONE CONSTANT, DERIVED, AND THE SCREEN PRINTS IT. `traceParamTable.ts:11-22` records that its own
 * total "has been mis-stated three different ways in prose" and binds the next writer to keep the
 * number in one place; the same rule applies to a smaller list for the same reason. Nothing in this
 * feature may write a count into a KDoc or into a sentence on screen except by reading this.
 */
val DW_TRACE_EXPORT_FORMAT_COUNT: Int = DW_TRACE_EXPORT_FORMATS.size

/**
 * The rows that may be filed on the record.
 *
 * DERIVED, NEVER A SECOND LIST. Flipping [DwTraceExportFormat.attachable] on a row is the whole of the
 * change needed to move a format between the two surfaces.
 */
val DW_TRACE_ATTACHABLE_FORMATS: List<DwTraceExportFormat> =
    DW_TRACE_EXPORT_FORMATS.filter { it.attachable }

/** The row for [id], or null. Case-sensitive: these ids are written into saved state. */
fun dwTraceExportFormat(id: String): DwTraceExportFormat? =
    DW_TRACE_EXPORT_FORMATS.firstOrNull { it.id == id }

/** One engine format this handset deliberately does not offer, and the reason. */
@Immutable
data class DwTraceExportAbsence(val engineFormat: String, val reason: String)

/**
 * The five members of `ExportFormat` this card does NOT offer, and why not.
 *
 * These are reasons rather than apologies — every one would be a fair thing to change if a designer
 * asked for it. Carried across from the web's `NOT_OFFERED` so that the two clients refuse the same
 * five things for the same five reasons; a format offered on one client and silently absent from the
 * other is a designer discovering the difference in front of an artisan.
 *
 * TEN IS THE WHOLE OF `ExportFormat`: five here plus [DW_TRACE_EXPORT_FORMAT_COUNT] offered. The web's
 * `e2e/sketch-export-formats-unit.spec.ts` holds that bijection shut on its side against the real
 * enum. `DwSketchTraceExportTest` can only hold the arithmetic on this side — it cannot see the
 * TypeScript enum — so the count is asserted here and the comparison against the enum stays the web
 * spec's job. That asymmetry is the honest one: nothing in the Kotlin tree can see the vendored enum,
 * and pretending otherwise would be a third register of somebody else's list.
 */
val DW_TRACE_NOT_OFFERED: List<DwTraceExportAbsence> = listOf(
    DwTraceExportAbsence(
        engineFormat = "JPEG",
        reason = "Lossy, and lossy is at its worst on exactly this: hard black edges on white come " +
            "back with grey mush around them. It carries no transparency either, so a traced drawing " +
            "would arrive on a white rectangle. `exportDocument` throws for it by design; PNG is the " +
            "raster answer.",
    ),
    DwTraceExportAbsence(
        engineFormat = "WEBP",
        reason = "The same lossy objection as JPEG, and it is a web delivery format rather than one a " +
            "print shop, a CAD package or a ministry office would take. `exportDocument` throws for " +
            "it too.",
    ),
    DwTraceExportAbsence(
        engineFormat = "TIFF",
        reason = "The engine writes it uncompressed — a ${DW_TRACE_PNG_MAX_EDGE_PX}px plate is " +
            "16 MB of RGBA — for a use nobody has asked for. PNG is the same pixels an order of " +
            "magnitude smaller, and the vector formats are the answer to “I need it bigger”.",
    ),
    DwTraceExportAbsence(
        engineFormat = "BMP",
        reason = "Uncompressed 32bpp, the same 16 MB, in a format whose only advantage is opening on " +
            "a computer from 1998. PNG covers every reader that would take a BMP.",
    ),
    DwTraceExportAbsence(
        engineFormat = "PROJECT",
        reason = "`.otproj` is the tracer's own session file — geometry plus every parameter, so " +
            "a trace can be reopened and re-tuned. It is not a drawing, it is useful only inside an " +
            "application the designer does not have, and `exportDocument` refuses it as well " +
            "(`ProjectCodec` writes it). Worth revisiting if this panel ever grows a “reopen " +
            "this trace” door; there is nothing to reopen it with today, because nothing on " +
            "this panel is stored.",
    ),
)

/* ────────────────────────────────────────────────────────────────────────────
 * The background, which is a property of the FILE and not of the trace
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **THERE IS ONE BACKGROUND AND IT IS `output.background`. THIS FILE DOES NOT DECLARE A SECOND.**
 *
 * ── WHY NOT, WHEN THE CONTROL LIVES ON THIS STEP ──────────────────────────────────────────────
 *
 * `DwSketchTraceParams.kt` relocates the "White background" toggle to `DwTraceTier.EXPORT`, which is
 * this card — but it relocates the CONTROL, not the VALUE. The value stays a leaf of the engine's own
 * parameter tree, sanitised by `sanitizeTraceParams` like every other leaf, with `null` as the only
 * spelling of transparent (`engine/params.ts:359`) and `DW_TRACE_OPAQUE_WHITE` as the spelling of
 * white. An `ExportBackground` enum here would be a SECOND path to one value, which is the exact
 * shape `traceParamTable.ts` refuses for `matte.mode = SUBJECT`: "two paths to one value means the two
 * can disagree about which decided".
 *
 * So the export reads the background as a FACT — [documentBackground] on a finished trace, which is
 * what the document stage actually used — and never as a choice of its own.
 *
 * ── AND READ OFF THE DOCUMENT, NOT OFF THE REQUEST ────────────────────────────────────────────
 *
 * `DwTraceDecoded.background` is what the pipeline WROTE, which is not always what was asked for:
 * auto-detection runs before the first stage, so `appliedParams` and the request can differ, and
 * `worker/protocol.ts:139-149` says dropping that distinction "would leave the client with a dock
 * that says one thing and a drawing produced by another". A file labelled White because a toggle says
 * White, over a document the engine wrote transparent, is that defect with a printer at the end of it.
 *
 * ── THE RULE THAT MAKES ALL FIVE FORMATS AGREE, WHICH IS THE POINT OF THIS SECTION ────────────
 *
 * Trap 1 in this file's header: `ExportOptions.background = null` means "leave the document alone" to
 * the vector writers and "transparent" to the rasteriser. Those coincide only when the document is
 * itself transparent. **So an exporter must pass the document's OWN background through, always, rather
 * than passing null.** Then:
 *
 *   * document transparent → vector keeps null → no `<rect>`; raster gets `null ?? 0` → transparent.
 *   * document white → `prepareVectorDoc` rebuilds the document with the same white it already had;
 *     raster clears to that white. Both white.
 *
 * Both arms agree for every value, by construction, with no special case. [dwTraceExportBackground] is
 * that pass-through, and it is a named function rather than a bare argument precisely because "pass
 * the document's background" and "pass null" look identical at a call site and are not.
 *
 * ── ONE CONSEQUENCE WORTH STATING ON SCREEN ───────────────────────────────────────────────────
 *
 * Because the value is a trace parameter and `output.background` is read at the twelfth and last
 * stage (`pipeline.ts:758`), changing it means running the pipeline again — twelve to twenty seconds
 * on the fleet's Galaxy M32 by the feasibility spike's own extrapolation. That is a real cost and
 * [DW_TRACE_BACKGROUND_RETRACE_SENTENCE] is where it is said. It is not a defect of the relocation:
 * `ExportOptions.background` could override it without a re-trace, but then the SVG already in hand
 * would disagree with the PDF beside it, and one drawing would leave this phone two ways.
 */
fun dwTraceExportBackground(documentBackground: Int?): Int? = documentBackground

/**
 * The document's background read off the parameters the pipeline ACTUALLY RAN WITH.
 *
 * ── WHY THIS EXISTS RATHER THAN THE CALLER READING THE MAP ────────────────────────────────────
 *
 * `DwTraceDecoded.background` is the document's own value and is the most direct answer, but it stops
 * at the runtime: `DwSketchTrace` builds a `DwTraceResult` out of a `DwTraceDecoded` and does not
 * carry the background across (verified 2026-08-27; re-check with
 * `grep -n "val background" android/.../DwSketchTraceEngine.kt`). What DOES cross is `appliedParams`,
 * and the two are the same number by construction — `pipeline.ts:758` assembles the document with
 * `p.output.background` and nothing else touches it.
 *
 * So this is the reading a surface can actually do today, and it is a function rather than three lines
 * at a call site because the narrowing is the kind of thing one caller gets right and the next does
 * not: the leaf is a `Double` (the parameter tree carries numbers), `4294967295.0` is opaque white,
 * and `.toLong().toInt()` is what turns it into the packed ARGB `Int` the engine and `Paint` both
 * want. `DwTraceStyle`'s KDoc in `DwSketchTraceWire.kt` states the same narrowing for stroke and fill:
 * "The narrowing IS the conversion, not a loss of information."
 *
 * ── AND WHY `appliedParams` AND NOT THE PARAMETERS THAT WERE SENT ─────────────────────────────
 *
 * Auto-detection runs before the first stage, so a request and its result can differ.
 * `worker/protocol.ts:139-149`: dropping that distinction "would leave the client with a dock that
 * says one thing and a drawing produced by another". A file labelled White because a toggle says
 * White, over a document the engine wrote transparent, is that defect with a printer at the end of it.
 *
 * @return packed ARGB, or null for a transparent document — the engine's only spelling of it.
 */
fun dwTraceDocumentBackground(appliedParams: DwTraceValues): Int? =
    appliedParams.number("output.background")?.toLong()?.toInt()

/** True when the traced document has a ground under it rather than being transparent. */
fun dwTraceBackgroundIsWhite(documentBackground: Int?): Boolean = documentBackground != null

/** "White" or "Transparent", for a line of copy. Never a control's own state — see above. */
fun dwTraceBackgroundLabel(documentBackground: Int?): String =
    if (dwTraceBackgroundIsWhite(documentBackground)) "White" else "Transparent"

/**
 * What changing the background costs, said where a designer can act on it.
 *
 * See the section above for why it costs a re-trace at all. Twelve to twenty seconds is the
 * feasibility spike's extrapolation to the fleet's Galaxy M32 for a non-FDOG full-resolution trace at
 * the product's 1600px input cap, measured on a laptop's V8 at 2.9 s and scaled by a factor that was
 * REASONED from published single-thread figures rather than measured on a device (2026-08-27). The
 * copy says "a few seconds" rather than a number for that reason; the number belongs in
 * `DwTraceAvailability.measuredOn`, where there is somewhere to say whether anybody has measured it.
 */
const val DW_TRACE_BACKGROUND_RETRACE_SENTENCE: String =
    "Changing this traces the sheet again, which takes a few seconds — the background is part of the " +
        "drawing the engine writes, so every format below changes together and none of them can " +
        "disagree with the others."

/* ────────────────────────────────────────────────────────────────────────────
 * Naming
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The word between the photograph's stem and the extension for a file FILED on the record.
 *
 * `sketch-line-art.svg` is what the record already holds on the web and what
 * `e2e/sketch-trace-panel.spec.ts` pins. Unchanged.
 */
const val DW_TRACE_ATTACH_SUFFIX: String = "line-art"

/**
 * The suffix for a saved VECTOR form — `.svg`, `.pdf`, `.dxf`, `.eps`.
 *
 * DELIBERATELY THE SAME WORD as [DW_TRACE_ATTACH_SUFFIX]. The SVG a designer saves is byte-for-byte
 * the file the record holds, and giving the copy on their phone a different name would invite the
 * belief that it is a different drawing; the other three are that same drawing written out for a
 * different machine, so they say so by sharing the word and are told apart by their extension, which
 * is what a designer reads a file by anyway.
 *
 * TWO CONSTANTS RATHER THAN ONE ALIAS, because they answer different questions: if the archive ever
 * renames its plate, the saved copy must not silently follow.
 */
const val DW_TRACE_SAVE_SUFFIX: String = "line-art"

/**
 * The suffix for the saved RENDERED raster.
 *
 * A DIFFERENT WORD, because a PNG named `-line-art.png` is exactly what an ATTACHED PNG is called, and
 * both would land in one Downloads folder where the record's own provenance is not there to tell them
 * apart.
 */
const val DW_TRACE_RENDER_SUFFIX: String = "traced"

/**
 * The suffix a [DwTraceExportFormat] takes when it is saved to the device.
 *
 * DERIVED FROM [DwTraceExportFormat.isVector] rather than from the id, so a sixth format added to the
 * table gets the right word by existing. The split is raster-versus-vector and not
 * attachable-versus-not: the question a suffix answers is "is this a picture OF the drawing, or the
 * drawing", and only the PNG is the former.
 */
fun dwTraceSaveSuffix(format: DwTraceExportFormat): String =
    if (format.isVector) DW_TRACE_SAVE_SUFFIX else DW_TRACE_RENDER_SUFFIX

/**
 * A name for the derived file, built from the photograph's own.
 *
 * The source name is kept and a suffix added, rather than a fresh name being invented, because the two
 * files sit in one record and a reviewer has to be able to tell which photograph a plate came from.
 * `DwSketchRectifyField`'s neighbour does the same for the same reason.
 *
 * ── A TRANSLITERATION OF `geometryToSvg.derivedFileName`, AND IT HAS TO BE ────────────────────
 *
 * Every rule below is that function's, in the same order: strip one extension, fall back to "sketch",
 * replace anything outside `[A-Za-z0-9_\-. ]` with `_`, cap the stem at 80 characters, sanitise the
 * suffix the same way, and join with a hyphen. `lib/media.ts`'s header is explicit about what two
 * naming implementations cost — "no two capture screens can drift into naming the same kind of file
 * differently" — and two CLIENTS is that hazard one register wider.
 *
 * Kotlin's `\w` is Java's `[a-zA-Z_0-9]`, which is JavaScript's, so the character classes match
 * exactly. `String.trim()` here strips characters up to and including space where JavaScript's strips
 * Unicode whitespace; the difference can only appear on an exotic space inside a filename, where it
 * would turn one character into `_` on one client — worth knowing about, not worth a second
 * implementation of `\s` to close.
 *
 * ── AND WHY THE WEB'S SANITISER IS SAFE FOR MediaStore, WHICH IS NOT OBVIOUS ──────────────────
 *
 * This name becomes `MediaStore.Downloads.DISPLAY_NAME`. `ReportExport.defaultName` uses a stricter
 * filter (`[^A-Za-z0-9._-]+`, no spaces) and its comment records why: "A craft name carrying a slash
 * (\"Ikat/Bandha\") or a colon produced a MediaStore insert that failed with a bare
 * IllegalArgumentException after the whole report had already been rendered." Both of those characters
 * are outside the web's class too and become `_` here. What survives here and not there is the SPACE,
 * which MediaStore has never objected to and which every photograph named by a phone gallery contains.
 * So the web's rule is kept — the two clients name one file one way — rather than tightened into a
 * rule that would rename it.
 *
 * [sourceName] may be a path: the last segment past either separator is taken first, because the
 * handset holds a photograph as an absolute path where the browser holds a `File.name`.
 */
fun dwTraceExportFileName(
    sourceName: String,
    extension: String,
    suffix: String = DW_TRACE_ATTACH_SUFFIX,
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val trimmed = leaf.replace(Regex("\\.[^./\\\\]+$"), "").trim()
    val base = if (trimmed.isNotEmpty()) trimmed else "sketch"
    val safe = base.replace(Regex("[^\\w\\-. ]+"), "_").take(80)
    val tag = suffix.replace(Regex("[^\\w\\-. ]+"), "_").trim()
    return if (tag.isNotEmpty()) "$safe-$tag.$extension" else "$safe.$extension"
}

/* ────────────────────────────────────────────────────────────────────────────
 * Provenance
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence written INTO a derived file, where the format has somewhere to put one.
 *
 * Word for word the web's `SketchTraceField.provenanceFor`, and shared by the attach and by the saved
 * copy for the reason that function gives: the saved copy is the one most likely to be mailed on,
 * printed, or opened in Illustrator by somebody who never saw this panel, so it is the copy that most
 * needs to be able to say what made it and from what.
 *
 * NOTHING IDENTIFYING. No designer, no workshop, no account, no timestamp beyond what is passed in.
 * `geometryToSvg.buildSvg`'s header sets that limit and the reason: this file is uploaded to a shared
 * archive and handed on, and a comment naming the person who traced it would be a disclosure nobody
 * asked for.
 *
 * [frameNote] is the crop sentence, and **THE HANDSET NOW HAS ONE.** This parameter spent its whole
 * life empty and said so; `DwTraceFramePanel` closed that, `dwTraceCropNote` builds the sentence in
 * `imageEdit.describeEdit`'s exact words, `DwTraceResult.frameNote` carries it out of the run, and
 * `DwSketchTraceExportCard` passes it in here. So the seam this parameter was declared to hold open
 * is now load-bearing rather than reserved. Re-check the chain with
 * `grep -rn "frameNote" android/app/src/main/java`; on 2026-08-27 it answers in five files — the
 * crop, the engine's result type, the runtime that fills it, this file, and the card.
 *
 * ONE HALF OF THE WEB'S SENTENCE AND NOT BOTH, DELIBERATELY. `describeEdit` builds two clauses, a
 * crop and an unsharp mask, and this handset has only the first: there is no sharpen control on
 * this client, so a sharpen clause here would describe an operation that never ran. The crop clause
 * is character for character the web's, which is the half that matters — the two files land in one
 * archive and a reviewer holding one of each must not have to decide whether two phrasings of
 * "cropped to 900x1200 at (30, 40)" mean two different operations.
 *
 * WHERE IT ACTUALLY LANDS, PER FORMAT: `/Title` in a PDF, `%%Title:` in an EPS, and nowhere at all in
 * a DXF or a PNG — `writeDxf` takes no metadata argument and `pngEncoder` writes no text chunk. The
 * SVG's title is not reachable either, because `exportFormats.exportSvg` builds its `SvgOptions` from
 * `precision`, `includeMetadata` and `groupByLayer` and passes no title through. Every one of those
 * absences is stated beside the file by [dwTraceExportLosses] rather than quietly tolerated.
 */
fun dwTraceProvenanceNote(
    sourceName: String,
    shapeCount: Int,
    nodeCount: Int,
    frameNote: String = "",
): String {
    val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "a photograph" }
    val head = "Traced on the device from $leaf by the Design & Prototype Workshop portal. " +
        "$shapeCount paths, $nodeCount nodes."
    return if (frameNote.isBlank()) head else "$head ${frameNote.trim()}"
}

/* ────────────────────────────────────────────────────────────────────────────
 * What each file does not carry
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **THE ONE SENTENCE ABOUT THE REPORT, PRINTED BY EVERY SURFACE IN THIS FEATURE.**
 *
 * See this file's header for the three modules that are the authority for it and for the re-check
 * commands. It is a constant and not five phrasings because the warning it is under is precisely that
 * this claim cannot be checked from the surface making it, so the only defence is that there is one
 * of it.
 *
 * WHAT IT DOES NOT SAY, ON PURPOSE. It does not say the export is useless and it does not tell the
 * designer to stop. Sending the drawing on is the whole point of the four take-away formats, and the
 * person who has to act is the designer, on the day — the same disposition
 * `ReportBuilder.attachments_named_but_not_carried` settles on: "the file is honest about what it
 * holds … and the person who has to act is the designer, on the day, whose action is to send those
 * files with it."
 */
const val DW_TRACE_EXPORT_REPORT_SENTENCE: String =
    "This file is yours to keep and to send on. It does not go into the workshop report: the report " +
        "prints photographs, and it names an attached file without carrying it — so an officer " +
        "reading the document will see that line art was produced and will not see this drawing " +
        "unless you send it to them."

/**
 * **THE SAME CLAIM ABOUT THE REPORT, FOR THE FILE THAT DOES REACH THE RECORD.**
 *
 * ── WHY A SECOND CONSTANT IS NOT A SECOND PHRASING ────────────────────────────────────────────
 *
 * This file's header warns at length against five surfaces each phrasing one claim, and counts the
 * five passes over one wrong version of it that `ReportBuilder.attachments_named_but_not_carried`
 * records. This is not that. [DW_TRACE_EXPORT_REPORT_SENTENCE] is about a SAVED copy — "yours to
 * keep and to send on", which reaches no field at all — and saying that of the SVG filed on
 * `sketch.lineArtFile` would be false in the other direction: that file is in the archive, an officer
 * can open it there, and nobody needs to be sent it. Two different files, two different fates, two
 * sentences. What they share is the backend authority above, which is why they are declared together.
 *
 * ── AND WHY IT EXISTS AT ALL, WHICH IS A CORRECTION ───────────────────────────────────────────
 *
 * The handset used to say the opposite of this, twice, on the only surface a designer can reach:
 * the trace panel's opening paragraph and its closing line both described the line art as "lines a
 * report can print at any size", which a designer reads as a promise that an officer will see the
 * drawing. The claim was already known to be false in this very lane — the header above establishes
 * it from three backend modules — and the correcting sentence was printed only by a card nothing
 * mounted. That is the exact failure the header predicts: "the same wrong sentence can be written …
 * any number of times without a single surface disagreeing with it."
 *
 * WORDING TAKEN FROM THE WEB, WHICH HAD IT AND THIS CLIENT DID NOT. `SketchTraceField.tsx`'s
 * "Attach as" block says "the choice does not change what the ministry report shows: the report
 * prints the sketch photograph, names the attached file and does not carry it. The file is in the
 * workshop record for whoever opens it there." Kept clause for clause; the handset adds only the two
 * facts it alone has — that the attached form is an SVG, and that the comparison plate above is
 * never what gets filed.
 */
const val DW_TRACE_ATTACH_REPORT_SENTENCE: String =
    "The drawing is attached as an SVG — vector line work that prints at any size without ever " +
        "going blocky. Attaching it does not change what the ministry report shows: the report " +
        "prints the sketch photograph, names the attached file and does not carry it, so the " +
        "drawing stays in the workshop record for whoever opens it there. The picture above is " +
        "only for comparing; it is never what gets attached."

/**
 * The sentence shown when the trace on screen is a PREVIEW rather than a full-resolution run.
 *
 * NAMES THE REMEDY, because a refusal that does not is a dead end.
 *
 * ── WHY A PREVIEW IS NEVER SAVED ──────────────────────────────────────────────────────────────
 *
 * A preview traces at a smaller working resolution so a slider can be judged in under a second. The
 * drawing it produces is not the drawing a full run produces — fewer paths, coarser corners — and
 * nothing inside an SVG, a PDF or a DXF says which it was. The web panel states the same rule as its
 * own fifth property (`SketchTraceField.tsx:64-70`): saving the preview "hands the designer a coarser
 * drawing than the one they approved, with nothing on screen to say so".
 *
 * It matters more on the handset than on the laptop, because the file here is going into a print
 * shop's workflow or an email to an officer, and "it came out blurry" discovered four days later at a
 * desk is not a recoverable failure — the sheet of paper is a fortnight away.
 */
const val DW_TRACE_EXPORT_PREVIEW_SENTENCE: String =
    "This drawing was traced at a smaller size so it could be tuned quickly. Trace it once at full " +
        "size before saving it — a preview saved now would be a coarser drawing than the one on " +
        "screen, and nothing in the file would say so."

/**
 * The sentence about the tracer's own name in the file's metadata.
 *
 * Trap 2 in this file's header is the whole argument. Printed for the format that carries it, so that
 * a designer meets it here rather than in an officer's inbox.
 *
 * ── IT WAS THREE FORMATS AND IT IS NOW ONE, BECAUSE THE WRITERS CHANGED UNDER IT ──────────────
 *
 * While the handset ran the engine as JavaScript, the SVG, the PDF and the EPS all carried the name.
 * The handset now runs the vendored Kotlin, and both places it is written pass `includeMetadata =
 * false` for exactly this reason — `dwTraceKotlinSvgOf` for the SVG, `DwTraceKotlinExporter` for the
 * other three. Read out of the vendored writers on 2026-08-28 rather than assumed:
 *
 *  * **SVG — clean.** `SvgExport` emits `<title>` and `<desc>` only under `includeMetadata`.
 *  * **PDF — clean.** `PdfWriter.kt:67` omits the whole `/Info` object under the same flag, so the
 *    `/Producer`, `/Creator` and `/Title` at `:135-136` are never written.
 *  * **DXF — clean.** `DxfWriter` writes no product name at all, under any option.
 *  * **PNG — clean.** `Bitmap.compress` writes no text chunk and is given no dpi.
 *  * **EPS — NOT clean, and no option makes it so.** `EpsWriter.kt:143` appends
 *    `%%Creator: Offline Tracer` OUTSIDE the `includeMetadata` guard that gates `%%Title` on the next
 *    line. Editing that file would break the SHA-256 in `android/UPSTREAM-MANIFEST-KOTLIN.txt` and the
 *    parity discipline it enforces, so the honest thing is to say it. `DwTraceKotlinExporterTest`
 *    asserts all five of these, so this list cannot rot into a claim about a file nobody read.
 */
const val DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE: String =
    "The file records that it was made by the Offline Tracer engine, which is the tracing library " +
        "this app uses on the device. That is a note about the software — not about the drawing, " +
        "and not about you."

/**
 * The sentence for a format that cannot say what it was traced from — which is now all five.
 *
 * ── IT WAS THREE, AND THE ENGINE SWAP MADE IT FIVE. THE OLD SENTENCE WAS A FALSE PROMISE ──────
 *
 * `dwTraceProvenanceNote` builds a line naming the photograph and the counts, and this constant used
 * to end "A PDF and an EPS have a title the note goes in". **That is no longer true and it was a
 * promise rather than a description**, so it is the first thing corrected here: a designer who read it
 * would have saved a PDF believing the photograph was named inside it.
 *
 * What changed is the writer, not the intent. The handset now writes those files with `:core-export`,
 * whose `ExportOptions` has ten fields and no title among them — so `PdfWriter` can only ever write
 * its own hard-coded `/Title` and `EpsWriter` its own `%%Title:`, both of which
 * `DwTraceKotlinExporter` switches off as another product's branding. There is no argument to pass
 * and nowhere for the note to go. Read out of those writers on 2026-08-28;
 * `DwTraceKotlinExporterTest` asserts that the note reaches none of the three files.
 *
 * The other two were always silent and their reasons are unchanged: `DxfWriter` takes no metadata
 * argument at all, and the PNG leaves through `Bitmap.compress`, which writes no text chunk.
 *
 * **THE PORTAL'S SVG STILL CARRIES IT**, which is a real difference between the two clients rather
 * than a hypothetical one: the web writes `sketch.lineArtFile` through its own
 * `frontend/components/sketches/upload/geometryToSvg.buildSvg`, which emits the note as an XML
 * comment. So the same drawing saved on a laptop names its source photograph and saved on a handset
 * does not. That is worth an owner's attention and it is stated here rather than left for somebody to
 * notice in a print shop.
 *
 * Written once and shared by every row for the reason `DwSketchChooserSentenceTest` pins from the
 * other side: two spellings of one fact is how the two drift.
 */
const val DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE: String =
    "This file records nothing about which photograph it was traced from. None of the five forms " +
        "this app writes has anywhere to put that note, so keep the photograph beside it if somebody " +
        "will need to know which sheet it came from."

/**
 * The sentence for a CROPPED trace saved in a format that cannot record the crop.
 *
 * ── WHY THIS IS A SECOND SENTENCE AND NOT A CLAUSE ON THE ONE ABOVE ───────────────────────────
 *
 * [DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE] is true of every save in those three formats and is a
 * fact about the FORMAT. This is true only of a designer who framed part of the sheet, and it is a
 * fact about THEIR drawing — the photograph in the record shows a whole sheet and the file in their
 * hand shows a corner of it, with nothing anywhere to say the two are the same sketch. Merging them
 * would put a conditional clause inside an unconditional sentence, which is how a reader learns to
 * skip the block; keeping them apart is `DwSketchChooserSentenceTest`'s rule from the other side.
 *
 * ── THE SET IT IS DRAWN FOR IS NOT THE WEB'S, AND THAT IS NOT A DIVERGENCE ────────────────────
 *
 * The portal warns for the PNG and the DXF, because its own SVG writer emits the note as an XML
 * comment. The handset writes every one of its five with a vendored writer that has nowhere to put a
 * title, so on this client all five are silent — which is exactly the set
 * [dwTraceExportLosses] gives [DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE] to, and this sentence is keyed
 * on that rather than on a second list of ids.
 *
 * ITS LAST CLAUSE USED TO NAME A REMEDY THAT NO LONGER EXISTS. It read "The note is written into a
 * PDF's title and an EPS's header", which sent a designer who had cropped a sheet to save a PDF for a
 * record the PDF does not keep. See [DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE] for what changed under
 * it. The remedy that IS true is the one this now names: keep the photograph beside the file.
 */
const val DW_TRACE_EXPORT_NO_FRAME_SENTENCE: String =
    "You traced part of the photograph rather than the whole sheet. This file has nowhere to record " +
        "that either, so somebody holding it beside the photograph cannot tell why the two do not " +
        "match. Say which part you traced when you send it on."

/**
 * Everything a designer should be told about the file they are about to save, beside the file.
 *
 * ── WHY BESIDE AND NOT INSIDE ─────────────────────────────────────────────────────────────────
 *
 * The same discipline `ReportScreen` already applies to a generated report, where `exportNotes` and
 * `exportLosses` print under "Saved" with the reason stated in that file: "A designer who is told the
 * PDF is not in their chosen typeface can send the .docx instead; the same difference unmentioned is a
 * defect somebody else notices first, in an office, about a document that has already been submitted."
 * Two of the five formats here have nowhere inside them to put a sentence at all, so beside is the
 * only place all five can be treated alike.
 *
 * ── WHAT IS DELIBERATELY NOT IN THE LIST ──────────────────────────────────────────────────────
 *
 * The hints in the table already say what each format IS. These are only the LOSSES — things that are
 * true of the bytes and invisible in them. A sentence that merely repeated the hint would train a
 * reader to skip the block that carries the one that matters, and the one that matters is always
 * first.
 *
 * [documentLongEdgePx] is the traced document's long edge, used only to decide whether the PNG's cap
 * actually bites on this drawing. Pass 0 when it is not known and the cap is stated unconditionally,
 * which is the safe direction: a cap named when it did not apply costs a reader one sentence, and a
 * cap that applied and was not named costs them a drawing softer than the one they approved.
 *
 * [frameNote] is `DwTraceResult.frameNote` — non-empty exactly when the designer traced part of the
 * photograph. It adds [DW_TRACE_EXPORT_NO_FRAME_SENTENCE] to the three formats that cannot carry it,
 * and nothing at all otherwise. Empty is the safe direction here in the OTHER direction from the cap
 * above: warning about a crop nobody made would teach a reader that this block describes situations
 * they are not in.
 */
fun dwTraceExportLosses(
    format: DwTraceExportFormat,
    /** `DwTraceDecoded.background` — what the document stage actually wrote. See [dwTraceExportBackground]. */
    documentBackground: Int?,
    documentLongEdgePx: Int = 0,
    frameNote: String = "",
): List<String> {
    val transparent = !dwTraceBackgroundIsWhite(documentBackground)
    val out = mutableListOf<String>()
    out += DW_TRACE_EXPORT_REPORT_SENTENCE
    when (format.id) {
        "png" -> {
            val capBites = documentLongEdgePx <= 0 || documentLongEdgePx > DW_TRACE_PNG_MAX_EDGE_PX
            if (capBites) {
                out += "Pixels, not curves, and no bigger than $DW_TRACE_PNG_MAX_EDGE_PX px on the " +
                    "long edge — a larger one is more memory than these phones have. Save the " +
                    "SVG instead if it has to be enlarged."
            }
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "dxf" -> {
            out += "DXF R12 carries geometry and nothing else: no colour, no fill, no line " +
                "thickness, and curves arrive as many short straight lines."
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
            out += "It carries no background either, so the choice above changes nothing in this file."
        }
        "eps" -> {
            out += "PostScript has no transparency. Anything part-see-through is flattened onto the " +
                "background as the file is written, and the engine records in the file that it did so."
            // THE ONLY FORMAT STILL BRANDED. `EpsWriter.kt:143` writes `%%Creator: Offline Tracer`
            // outside the `includeMetadata` guard, so no option removes it — see that constant, which
            // reads all five writers and says which are clean.
            out += DW_TRACE_EXPORT_ENGINE_NAME_SENTENCE
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "pdf" -> {
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
        "svg" -> {
            out += DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE
        }
    }
    if (frameNote.isNotBlank() && DW_TRACE_EXPORT_NO_PROVENANCE_SENTENCE in out) {
        // KEYED ON THE PROVENANCE SENTENCE ALREADY BEING IN THE LIST rather than on a second literal
        // set of ids. The question both answer is the same one — "has this file anywhere to put a
        // sentence about how it was made" — and a second list of the three silent formats is the
        // register that goes stale the day a fourth is added, which §16 of the frontend skill records
        // happening for months.
        out += DW_TRACE_EXPORT_NO_FRAME_SENTENCE
    }
    if (transparent && format.id != "dxf") {
        // DRAWN FOR THE PNG TOO, unlike an earlier reading of this rule. A transparent PNG dropped into
        // a letter shows the page through it, which is the same surprise a transparent SVG produces in
        // a PDF — the format that genuinely cannot express it is the DXF, which carries no background
        // at all, and that one is named on its own row above.
        out += "Transparent means the page shows through wherever the drawing is not. In an email or " +
            "a report that is whatever colour the reader's page happens to be — turn the white " +
            "background on above if somebody is going to print it."
    }
    return out
}
