package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.CalloutBlock
import com.designprototype.workshop.report.ChartBlock
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MapBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SignatureBlock
import com.designprototype.workshop.report.SpacerBlock
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TocBlock
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.io.File

/**
 * The report, drawn on the handset from the document the handset is about to write.
 *
 * Asked for on 2026-08-25: *"Provide the same live document preview functionality on Android,
 * ensuring that the generated document can be previewed directly from the Android application as
 * well."*
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * IT IS NOT A NEW RENDERER, AND ON THIS PLATFORM THAT CLAIM IS STRONGER THAN IT IS ON THE WEB
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web's preview has to ask the server to build the document, because the browser has no report
 * builder in it. This handset does: `buildWorkshopDocument` walks the registry over the local draft
 * and produces the very [ReportDocument] that `DocxWriter` and `PdfWriter` then write. So the caller
 * hands that same document here and this file draws its `blocks`. Nothing is re-derived from stage
 * data, no second traversal exists, and there is no round trip — which is why the preview works in a
 * courtyard with no signal, where the web's cannot and says so.
 *
 * The consequence worth stating: on Android the preview is live against the DRAFT, so it reflects
 * what the designer has typed as soon as the stage is saved to the device — no sync required. That is
 * a genuine platform difference from the web (whose preview follows what the SERVER holds) and it is
 * the honest one rather than a paraphrase: the two clients have different builders available to
 * them, and this one is offline-first by design.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS A FLOW OF BLOCKS AND NOT A4 SHEETS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web's `/report` page lays blocks onto A4 at real millimetre dimensions, because it answers a
 * question about PAGES — has the cover's info table pushed the hero photograph onto page two. That
 * question cannot be answered on a 360dp-wide screen: an A4 sheet rendered to fit is 4pt type, and
 * rendered at readable type it needs horizontal scrolling, which is a gesture nobody can aim on a
 * phone while reading. So this draws the document as a readable column and says so on screen. A
 * designer who needs to check pagination has the .pdf export two taps away, on the same screen.
 *
 * PAGE BREAKS ARE DRAWN AS A MARKED RULE rather than being ignored. A `PAGEBREAK` is a break the
 * template ASKED for and both writers honour it exactly, so it is the one piece of page structure
 * that is knowable here — and a preview that silently dropped it would let a designer arrange two
 * sections believing they sit together when the file will always split them.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FIGURES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * MAP and CHART blocks are drawn as a NAMED PLACEHOLDER carrying their title and their own summary,
 * not as an empty box and not as an invented drawing. `ReportChart.kt` and `ReportMap.kt` rasterise
 * these into the file, and re-implementing either in Compose would be a second chart engine on one
 * device whose output nobody compares against the first. The placeholder says which figure it is and
 * that the file carries it drawn — which is what a designer proofing prose needs to know, and it is
 * honest about what this screen is not showing. Rule 10: a gap that says nothing reads as a gap in
 * the document.
 */

/** How much of the theme is honoured here. See [DwReportPreview] for what the accent is used on. */
private fun accentColor(hex: String?): Color? {
    val cleaned = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return runCatching { Color(("ff$cleaned").toLong(16)) }.getOrNull()
}

/**
 * Report runs as one styled string. The four marks the model carries, and nothing invented.
 *
 * EVERY CALL SITE PASSES THE `AnnotatedString` ITSELF, and that is the whole point of building one.
 * Until 2026-08-26 all six of them ended in `.text` — and the table cells did not call this at all,
 * flattening with `joinToString("") { it.text }` — so this function assembled bold, italic,
 * underline and strikethrough and then every reader threw them away. Nothing failed and nothing
 * looked wrong: a run's marks are the kind of thing a reader only misses when they compare the
 * preview with the .docx it is a preview OF, which is exactly the comparison this screen exists to
 * let a designer make in a courtyard rather than back at a desk.
 *
 * The `Text` these call sites resolve to is `ui/FieldText.kt`'s `AnnotatedString` overload, which
 * exists for precisely this. An outer `fontWeight` still applies as the base style — the total row
 * of a table stays `SemiBold` — and a span overrides it per run.
 */
@Composable
private fun runsToAnnotated(runs: List<Run>) = remember(runs) {
    buildAnnotatedString {
        runs.forEach { run ->
            withStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        run.underline && run.strike ->
                            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        run.underline -> TextDecoration.Underline
                        run.strike -> TextDecoration.LineThrough
                        else -> null
                    }
                )
            ) { append(run.text) }
        }
    }
}

@Composable
internal fun DwReportPreview(
    document: ReportDocument,
    /**
     * Resolve an [com.designprototype.workshop.report.ImageRef.source] token to a file on this
     * device, or null.
     *
     * PASSED IN RATHER THAN REACHED FOR, exactly as [DwMediaBridge] is on the form side: this file
     * has no business knowing which workshop it is drawing and must not be able to read another
     * one's media directory. Null is ORDINARY and not an error — a photograph attached in the browser
     * has no local copy — and it draws a named placeholder rather than a broken frame.
     */
    resolveImage: (String) -> File?,
    modifier: Modifier = Modifier,
) {
    val accent = accentColor(document.theme.accent)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        document.blocks.forEachIndexed { index, block ->
            DwReportBlock(block = block, accent = accent, resolveImage = resolveImage, key = index)
        }
    }
}

@Composable
private fun DwReportBlock(
    block: Block,
    accent: Color?,
    resolveImage: (String) -> File?,
    key: Int,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.field.muted

    // EXHAUSTIVE `when` ON A SEALED INTERFACE, with no `else`. That is what makes the compiler point
    // at this file the day a seventeenth block type is added to the model — the same discipline
    // `FieldInput`'s switch over `DwFieldType` keeps on the web. A block type this screen cannot draw
    // must be a build failure, not a silently missing section of a document.
    when (block) {
        is CoverBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text(block.title, display = true, color = ink, fontSize = 20.sp)
            if (block.subtitle.isNotBlank()) Text(block.subtitle, color = muted, fontSize = 13.sp)
            block.orgLines.forEach { line -> Text(line, color = muted, fontSize = 12.sp) }
            block.heroImage?.let { hero ->
                val file = resolveImage(hero.source)
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Cover photograph",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(hero.aspect)
                    )
                } else {
                    MissingFigure("Cover photograph")
                }
            }
            if (block.infoRows.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                // The cover's rows are plain `Pair<String, String>` in the document model — they carry no
                // runs and so no marks — while [KeyValueBlock] below hands this the styled string
                // [runsToAnnotated] builds. One row renderer, wrapped here rather than overloaded.
                block.infoRows.forEach { (label, value) -> KeyValueLine(label, AnnotatedString(value), muted, ink) }
            }
            block.footerLines.forEach { line -> Text(line, color = muted, fontSize = 11.sp) }
        }

        // THE TABLE OF CONTENTS IS NAMED, NOT SYNTHESISED. Both writers build it from the headings
        // they emit and from their own page numbers; a list of headings with no page numbers beside
        // them would be a different object wearing the same title.
        is TocBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(block.title, display = true, color = ink, fontSize = 15.sp)
            Text(
                "Built with page numbers when the file is written.",
                color = muted,
                fontSize = 11.sp
            )
        }

        is HeadingBlock -> {
            val text = runsToAnnotated(block.runs)
            Text(
                if (block.number.isBlank()) text else buildAnnotatedString {
                    append("${block.number}  ")
                    append(text)
                },
                display = true,
                color = accent ?: ink,
                // The four levels, kept in the same ORDER of sizes the writers use. Exact points are
                // the file's business; what has to survive here is the hierarchy a reader navigates by.
                fontSize = when (block.level) {
                    1 -> 19.sp
                    2 -> 16.sp
                    3 -> 14.sp
                    else -> 13.sp
                },
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp)
            )
        }

        is ParagraphBlock -> Text(
            runsToAnnotated(block.runs),
            color = if (block.style.name.contains("CAPTION")) muted else ink,
            fontSize = if (block.style.name.contains("CAPTION")) 11.sp else 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (block.align == Align.CENTER) 0.dp else 0.dp)
        )

        is BulletListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEachIndexed { at, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (block.ordered) "${at + 1}." else "•",
                        color = accent ?: muted,
                        fontSize = 13.sp
                    )
                    Text(runsToAnnotated(item), color = ink, fontSize = 13.sp)
                }
            }
        }

        is KeyValueBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.pairs.forEach { (label, runs) ->
                KeyValueLine(label, runsToAnnotated(runs), muted, ink)
            }
        }

        /*
          A TABLE, SCROLLED INSIDE ITSELF AND NEVER WRAPPED INTO A LIST OF CARDS.
          Turning rows into cards is the usual phone answer and it is the wrong one for a document
          preview: the designer is checking that a cost sheet's columns line up and that the total row
          agrees with the rows above it, which is a question about a TABLE. So the columns stay, in the
          template's declared proportions, and the block scrolls horizontally within its own bounds.
        */
        is TableBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                block.columns.forEach { column ->
                    Text(
                        column.header,
                        color = accent ?: ink,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(column.widthPct.coerceAtLeast(1f))
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.field.hairline)
            block.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    block.columns.forEachIndexed { at, column ->
                        Text(
                            runsToAnnotated(row.getOrNull(at).orEmpty()),
                            color = ink,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(column.widthPct.coerceAtLeast(1f))
                        )
                    }
                }
            }
            block.totalRow?.let { total ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    block.columns.forEachIndexed { at, column ->
                        Text(
                            runsToAnnotated(total.getOrNull(at).orEmpty()),
                            color = ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(column.widthPct.coerceAtLeast(1f))
                        )
                    }
                }
            }
        }

        is ImageBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val file = resolveImage(block.image.source)
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = block.caption.ifBlank { "Report photograph" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(block.widthPct.coerceIn(10f, 100f) / 100f)
                        .aspectRatio(block.image.aspect)
                )
            } else {
                MissingFigure(block.caption.ifBlank { "Photograph" })
            }
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, fontSize = 11.sp)
        }

        is ImageGridBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Chunked to the template's own column count, so a four-up grid in the file reads as a
            // four-up grid here rather than as a single column of unrelated photographs.
            block.images.chunked(block.columns.coerceAtLeast(1)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (ref, caption) ->
                        Column(modifier = Modifier.weight(1f)) {
                            val file = resolveImage(ref.source)
                            if (file != null) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(88.dp)
                                )
                            } else {
                                MissingFigure(caption.ifBlank { "Photograph" })
                            }
                            if (caption.isNotBlank()) Text(caption, color = muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            // THE GALLERY'S NAME GOES BELOW ITS PLATE, not above it, because that is where all five
            // other surfaces draw it: report_docx.py and DocxWriter.kt both emit the caption paragraph
            // after the table, PdfWriter.kt calls `caption(...)` after the row loop, report_pdf.py does
            // the same, and ReportBlock.tsx puts its `<figcaption>` after the closing `.rp-grid`. The ImageBlock
            // arm just above already reads this way. This file's header disclaims A4 pagination and
            // figure rasterisation only — it claims to draw the blocks faithfully — so a caption that
            // led its own grid here made the preview the one surface that read differently.
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, fontSize = 11.sp)
        }

        is MetricRowBlock -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            block.metrics.forEach { metric ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(metric.second, display = true, color = accent ?: ink, fontSize = 17.sp)
                    Text(metric.first, color = muted, fontSize = 10.sp)
                    if (metric.third.isNotBlank()) Text(metric.third, color = muted, fontSize = 10.sp)
                }
            }
        }

        // See the header: a named placeholder rather than a second chart engine or an empty box.
        is ChartBlock -> FigurePlaceholder(
            name = block.title.ifBlank { "Chart" },
            detail = "Drawn in the exported file."
        )
        is MapBlock -> FigurePlaceholder(
            name = block.title.ifBlank { "Map" },
            detail = "Drawn in the exported file."
        )

        is CalloutBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            if (block.title.isNotBlank()) {
                Text(block.title, color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(runsToAnnotated(block.runs), color = ink, fontSize = 12.sp)
        }

        is SignatureBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.signatories.forEach { (name, role) ->
                Column {
                    // The rule a signature goes ON, drawn as the rule it is. A name with nothing under
                    // it does not read as a signature block, and this is the part of the document an
                    // officer counter-signs.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(1.dp)
                            .background(MaterialTheme.field.hairline)
                            .padding(top = 12.dp)
                    )
                    Text(name, color = ink, fontSize = 12.sp)
                    if (role.isNotBlank()) Text(role, color = muted, fontSize = 11.sp)
                }
            }
        }

        is SpacerBlock -> Box(modifier = Modifier.height((block.heightPct.coerceIn(1f, 40f)).dp))

        is PageBreakBlock -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            HorizontalDivider(color = MaterialTheme.field.hairline, modifier = Modifier.weight(1f))
            Text("page break", color = muted, fontSize = 10.sp)
            HorizontalDivider(color = MaterialTheme.field.hairline, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyValueLine(label: String, value: AnnotatedString, muted: Color, ink: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = muted, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = ink, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
    }
}

/**
 * A photograph this document carries a reference to and this screen cannot draw.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHICH CASE THIS ACTUALLY IS — AND THE SENTENCE IT USED TO CARRY WAS ABOUT A DIFFERENT ONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It said *"not on this device; the exported file will fetch it."* Both halves were wrong, and the
 * second half was wrong in a way that loses photographs out of a submitted document.
 *
 * **THE EXPORT ON THIS HANDSET CANNOT FETCH ANYTHING.** `DocxWriter`'s image loader resolves a media
 * id against LOCAL storage only, records every miss in `ReportExport.Result.droppedImages` and has no
 * network path of any kind — the whole point of building the report on the device is that it works in
 * a courtyard with no signal. A designer who read that sentence, exported, and handed the file over
 * had been told the gaps would fill themselves; they do not, and nothing in the delivered .docx says
 * a plate is missing.
 *
 * **AND "NOT ON THIS DEVICE" IS NOT THE CASE THAT REACHES HERE AT ALL.** A photograph attached in the
 * browser — a workshop captured on a colleague's phone, read back through `reportSourceFor` — carries
 * the SERVER's media id, which `buildWorkshopDocument`'s `imageFor` answers null for. `imagesOf` then
 * does `imageFor(id) ?: continue`, so no `ImageRef` is ever built for it, so no [ImageBlock],
 * [ImageGridBlock] cell or [CoverBlock.heroImage] exists for this composable to be reached from. That
 * loss is counted by `onUnresolvedMedia` and said in words on the export screen and in the preview's
 * own header — see `unresolvedMediaNote`, which is the sentence for it — and it never comes here.
 *
 * What DOES reach here is the narrow case where the builder resolved the id to a real file on this
 * device (`imageFor` requires `file.exists()`) and `resolveImage` cannot open that file when the
 * block is drawn: a capture deleted, moved or truncated between the two moments. That is the same
 * failure `droppedImages` counts on the export side, and the export will drop it too — which is what
 * the sentence now says, because it is the thing that is true and the thing a designer can act on.
 *
 * NAMED, NEVER BLANK. A grey rectangle in a preview reads as a photograph that failed to attach, and
 * this says which figure it is, that the reference itself is intact, and what the file will do with
 * it. Rule 10: a gap that says nothing reads as a gap in the document.
 */
@Composable
private fun MissingFigure(name: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            // `heightIn` AND NOT `height`. The honest sentence is three times as long as the false
            // one, and a fixed 72dp box clips text at the small screen widths this preview exists
            // for — a truncated warning about a truncation is the defect twice over.
            .heightIn(min = 72.dp)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
    ) {
        Text(
            "$name — this device cannot read the file, so it is not drawn here and the export will " +
                "leave it out of the document as well. Nothing on the handset can fetch it back.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** A figure the file rasterises and this screen deliberately does not redraw. See the header. */
@Composable
private fun FigurePlaceholder(name: String, detail: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.field.muted, fontSize = 11.sp)
    }
}
