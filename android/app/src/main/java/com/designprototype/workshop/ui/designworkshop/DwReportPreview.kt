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
import coil3.compose.AsyncImage
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

internal fun dwAccentColor(hex: String?): Color? {
    val cleaned = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return runCatching { Color(("ff$cleaned").toLong(16)) }.getOrNull()
}

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
    resolveImage: (String) -> File?,
    modifier: Modifier = Modifier,
) {
    val accent = dwAccentColor(document.theme.accent)
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
internal fun DwReportBlock(
    block: Block,
    accent: Color?,
    resolveImage: (String) -> File?,
    key: Int,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.field.muted

    val probe = LocalDwFlowProbe.current

    when (block) {
        is CoverBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text(block.title, display = true, color = ink, style = MaterialTheme.typography.titleLarge)
            if (block.subtitle.isNotBlank()) Text(block.subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
            block.orgLines.forEach { line -> Text(line, color = muted, style = MaterialTheme.typography.bodySmall) }
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
                block.infoRows.forEach { (label, value) -> KeyValueLine(label, AnnotatedString(value), muted, ink) }
            }
            block.footerLines.forEach { line -> Text(line, color = muted, style = MaterialTheme.typography.bodySmall) }
        }

        is TocBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(block.title, display = true, color = ink, style = MaterialTheme.typography.titleMedium)
            Text(
                "Page numbers are added on export.",
                color = muted,
                style = MaterialTheme.typography.bodySmall
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
                style = when (block.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    3 -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp)
            )
        }

        is ParagraphBlock -> Text(
            runsToAnnotated(block.runs),
            color = if (block.style.name.contains("CAPTION")) muted else ink,
            style = if (block.style.name.contains("CAPTION")) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            onTextLayout = { probe?.textLines(key, it) },
            modifier = Modifier
                .fillMaxWidth()
                .dwTextTop(probe, key)
                .padding(start = if (block.align == Align.CENTER) 0.dp else 0.dp)
        )

        is BulletListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEachIndexed { at, item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.dwBreakStop(probe, key, at),
                ) {
                    Text(
                        if (block.ordered) "${at + 1}." else "•",
                        color = accent ?: muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(runsToAnnotated(item), color = ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is KeyValueBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.pairs.forEachIndexed { at, pair ->
                KeyValueLine(
                    pair.first,
                    runsToAnnotated(pair.second),
                    muted,
                    ink,
                    modifier = Modifier.dwBreakStop(probe, key, at),
                )
            }
        }

        is TableBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                block.columns.forEach { column ->
                    Text(
                        column.header,
                        color = accent ?: ink,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(column.widthPct.coerceAtLeast(1f))
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.field.hairline,
                modifier = Modifier.dwHeaderBottom(probe, key),
            )
            block.rows.forEachIndexed { at, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().dwBreakStop(probe, key, at),
                ) {
                    block.columns.forEachIndexed { at2, column ->
                        Text(
                            runsToAnnotated(row.getOrNull(at2).orEmpty()),
                            color = ink,
                            style = MaterialTheme.typography.bodySmall,
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
                            style = MaterialTheme.typography.bodySmall,
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
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, style = MaterialTheme.typography.bodySmall)
        }

        is ImageGridBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.images.chunked(block.columns.coerceAtLeast(1)).forEachIndexed { at, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().dwBreakStop(probe, key, at),
                ) {
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
                            if (caption.isNotBlank()) Text(caption, color = muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (block.caption.isNotBlank()) Text(block.caption, color = muted, style = MaterialTheme.typography.bodySmall)
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
                    Text(metric.second, display = true, color = accent ?: ink, style = MaterialTheme.typography.titleLarge)
                    Text(metric.first, color = muted, style = MaterialTheme.typography.labelSmall)
                    if (metric.third.isNotBlank()) Text(metric.third, color = muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

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
                Text(block.title, color = ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                runsToAnnotated(block.runs),
                color = ink,
                style = MaterialTheme.typography.bodySmall,
                onTextLayout = { probe?.textLines(key, it) },
                modifier = Modifier.fillMaxWidth().dwTextTop(probe, key),
            )
        }

        is SignatureBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.signatories.forEach { (name, role) ->
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(1.dp)
                            .background(MaterialTheme.field.hairline)
                            .padding(top = 12.dp)
                    )
                    Text(name, color = ink, style = MaterialTheme.typography.bodySmall)
                    if (role.isNotBlank()) Text(role, color = muted, style = MaterialTheme.typography.bodySmall)
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
            Text("page break", color = muted, style = MaterialTheme.typography.labelSmall)
            HorizontalDivider(color = MaterialTheme.field.hairline, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyValueLine(
    label: String,
    value: AnnotatedString,
    muted: Color,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(label, color = muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        Text(value, color = ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun MissingFigure(name: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
    ) {
        Text(
            "Couldn't open $name, so it's left out of the report.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun FigurePlaceholder(name: String, detail: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
}
