package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.resolveAccent
import com.designprototype.workshop.report.resolveTemplateId
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How long after the last edit the document is rebuilt — the web's `LIVE_DEBOUNCE_MS`, to the ms.
 * Long enough that a word typed at speed is one rebuild, short enough that the page answers before
 * the designer's eyes reach it.
 */
internal const val DW_LIVE_PREVIEW_DEBOUNCE_MS = 250L

/**
 * THE STAGE'S PAGES, LIVE, BESIDE THE FORM — the handset's counterpart of the web's
 * `StageDocumentPreview`.
 *
 * COMPOSE-NATIVE, NOT A WEBVIEW, and the choice is about fidelity as much as about the network. The
 * handset already lays the report out on A4 sheets ([DwReportSheets]) with the same packing rules
 * and the same block renderer it uses on the report screen, from the same Kotlin builder that writes
 * its .docx and .pdf ([buildWorkshopDocument]) — so the pages here are the pages the phone's own file
 * will have. A WebView would need the web app's renderer bundled into the APK to work with no
 * signal, and would then be drawing a SECOND layout of a document this device already knows how to
 * lay out, with a different text engine and different line breaks.
 *
 * Built from [liveDraft] — the draft on disk with this stage's unsaved answers laid over it —
 * [DW_LIVE_PREVIEW_DEBOUNCE_MS] after the last edit, on `Dispatchers.Default`. The sheets keep the
 * previous document up until the new one is measured, so typing never flashes an empty panel.
 */
@Composable
internal fun DwStageDocumentPreview(
    workshopId: String,
    stageTitle: String,
    schema: SchemaResponse?,
    /** Bumped on every edit; a new value restarts the debounce. */
    revision: Int,
    /** The draft as it would be if this stage were saved now. Null while there is none. */
    liveDraft: suspend () -> WorkshopDraft?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var open by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var document by remember(workshopId) { mutableStateOf<ReportDocument?>(null) }
    var failed by remember(workshopId) { mutableStateOf(false) }

    LaunchedEffect(open, revision, schema) {
        if (!open || schema == null) return@LaunchedEffect
        // The first build after opening is immediate; every later one waits for typing to pause.
        if (document != null) delay(DW_LIVE_PREVIEW_DEBOUNCE_MS)
        try {
            val draft = liveDraft() ?: return@LaunchedEffect
            val built = withContext(Dispatchers.Default) {
                val settings = draft.stages["REPORT_GENERATION"]?.values.orEmpty()
                buildWorkshopDocument(
                    context = appContext,
                    schema = schema,
                    draft = draft,
                    workshopId = workshopId,
                    templateId = resolveTemplateId(null, settings, draft.templateId).ifEmpty { "DCH_STANDARD" },
                    warnings = emptyList(),
                    accent = resolveAccent(null, settings).orEmpty(),
                )
            }
            document = built
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The last good pages stay up; the line below says the newest edit is not in them.
            failed = true
        }
    }

    val resolveImage: (String) -> File? = remember { { source: String -> File(source).takeIf { it.exists() } } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Document preview", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Hide the document preview" else "Show the document preview",
            )
        }
        if (open) {
            Text(
                "Updates as you type — the pages this stage falls on, as this device writes the report.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (failed) {
                Text(
                    "The newest change couldn't be laid out just now; the pages below are from the change before it.",
                    color = MaterialTheme.field.warning,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            val current = document
            if (current == null) {
                Text("Building the document…", color = MaterialTheme.field.muted, fontSize = 12.sp)
            } else {
                val hasStage = remember(current, stageTitle) { stageBlockRange(current.blocks, stageTitle) != null }
                if (!hasStage) {
                    Text(
                        "This stage has no heading of its own in the document yet — usually because nothing in it has " +
                            "been filled in. The whole document is shown instead.",
                        color = MaterialTheme.field.warning,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    Text(
                        if (showAll) "Show only this stage's pages" else "Show every page",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 4.dp),
                    )
                }
                DwReportSheets(
                    document = current,
                    resolveImage = resolveImage,
                    focusStageTitle = if (showAll || !hasStage) null else stageTitle,
                )
            }
        }
    }
}
