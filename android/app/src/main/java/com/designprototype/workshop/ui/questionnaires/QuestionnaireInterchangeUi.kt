package com.designprototype.workshop.ui.questionnaires

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwQuestionnaireArtefact
import com.designprototype.workshop.data.QFormChangeReportDto
import com.designprototype.workshop.data.artefactContents
import com.designprototype.workshop.data.artefactCarriesRespondents
import com.designprototype.workshop.data.qFormProblemLine
import com.designprototype.workshop.data.qFormProblemsToShow
import com.designprototype.workshop.data.qFormProvenanceNotice
import com.designprototype.workshop.data.qFormUploadSummary
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * The .xlsx interchange, on the handset: the three downloads, the two uploads, and the report.
 *
 * ── WHY THESE CONTROLS ARE HERE AT ALL ───────────────────────────────────────────────────────
 *
 * They were deliberately absent until 2026-08-16 and the decision was reversed by the person the app
 * is for. The whole argument, including the part of the original reasoning that was wrong, is
 * written where the endpoints are bound — see the block comment in `data/WorkshopRepositoryApi.kt`
 * — rather than repeated here.
 *
 * ── THE ONE RULE THIS FILE ENFORCES ──────────────────────────────────────────────────────────
 *
 * WHICH FILE CARRIES RESPONDENTS IS NEVER BEHIND A DISCLOSURE. Two of the three downloads are named
 * after the same questionnaire, land in the same Downloads folder, and are one tap apart on this
 * screen; one of them is a question list and the other is every person a designer has ever
 * interviewed. [ArtefactNotice] therefore prints that sentence next to the button, always, and the
 * amber is on the download that carries people rather than on some generic "careful" banner —
 * because a warning that appears over all three teaches nobody which one it is about.
 *
 * The button labels carry it too. "Download .xlsx" beside "Download question set" is two names for
 * what a designer reads as the same thing, so the workbook's control says what it holds.
 */

/**
 * What a download contains, stated beside the control that fetches it.
 *
 * The load-bearing half — whether this artefact carries the people who were interviewed — is a
 * separate, coloured line and is never joined onto the end of the description. A designer scanning
 * this screen with a colleague waiting is reading two words, not a paragraph, and the two words have
 * to be the ones that decide whether the file may be sent.
 */
@Composable
internal fun ArtefactNotice(artefact: DwQuestionnaireArtefact) {
    val carries = artefactCarriesRespondents(artefact)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            artefactContents(artefact),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Text(
            if (carries) {
                "This file carries respondents' names and their answers."
            } else {
                "This file carries nobody's name and no answers."
            },
            color = if (carries) MaterialTheme.field.warning else MaterialTheme.field.muted,
            fontSize = 11.sp,
            fontWeight = if (carries) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * One download: the button, what the file holds, and where it landed.
 *
 * [busy] is the SCREEN's busy flag and not this control's, so a designer cannot start a second
 * download on top of the first — two MediaStore writes to the same Downloads name racing each other
 * is how one of them ends up truncated with no error anywhere.
 *
 * The saved location is shown rather than a bare "Saved". A file in `Downloads/` that the designer
 * cannot find is a file they will download again, and on a handset with a dozen file managers the
 * path is the only thing that answers "where".
 */
@Composable
internal fun ArtefactDownloadRow(
    label: String,
    artefact: DwQuestionnaireArtefact,
    busy: Boolean,
    working: Boolean,
    savedTo: String?,
    onDownload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDownload,
                enabled = !busy,
                // The 48dp floor this app applies wherever a control was thought about — see
                // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt.
                modifier = Modifier.heightIn48(),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Saving…" else label, fontSize = 13.sp)
            }
        }
        ArtefactNotice(artefact)
        savedTo?.let {
            Text("Saved to $it", color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
    }
}

/**
 * Pick a workbook off the device and hand its Uri back.
 *
 * `OpenDocument` AND NOT `GetContent`, which matters on this particular picker. `GetContent` can
 * return a Uri whose permission dies with the activity result, and this flow reads the bytes inside
 * a coroutine that outlives the callback; `OpenDocument` grants a persistable read that is still
 * valid when the upload actually runs. A designer whose 40-question upload failed with "could not
 * be opened" would have no way to tell that from a corrupt file.
 *
 * THE MIME FILTER IS DELIBERATELY WIDE. The correct type for an .xlsx is a 74-character string, and
 * the providers a designer actually picks from — a downloads folder, a chat app's saved-files
 * folder, an SD card — routinely report `application/octet-stream` for exactly the same bytes. A
 * strict filter would grey out the file the designer is looking straight at, with no explanation
 * available anywhere on the screen. The server parses the workbook and refuses what is not one,
 * with a sentence; that is the right place for the check, because it is the place that can read the
 * file.
 */
@Composable
internal fun rememberWorkbookPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        launcher.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream",
                "*/*",
            )
        )
    }
}

/** The "pick a workbook" button, with the sentence saying which files this door accepts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbookUploadRow(
    label: String,
    blurb: String,
    busy: Boolean,
    working: Boolean,
    onPick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.heightIn48()) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Reading the workbook…" else label, fontSize = 13.sp)
            }
        }
        Text(blurb, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

/**
 * What the upload did, in the server's own words.
 *
 * ── WHY THIS IS A PANEL AND NOT A TOAST ──────────────────────────────────────────────────────
 *
 * The route's own docstring says the problem list IS the feature: "A designer who uploads forty
 * questions and is shown thirty-eight, with no way to find out which two are missing or why, does
 * not trust the import again". A snackbar that says "Uploaded" and vanishes in four seconds is
 * precisely that failure with a friendlier surface, so this stays on screen until the designer
 * dismisses it.
 *
 * ── THE THREE THINGS IT SAYS, IN THIS ORDER ──────────────────────────────────────────────────
 *
 * 1. THE TALLY. What changed, counted, non-zero terms only.
 * 2. THE PROVENANCE. What happened to the ANSWERS, which is the one outcome a designer cannot infer
 *    from the question list in front of them — a workbook that came out of the platform imports its
 *    questions and NOT its answers, and the reason is a paragraph the server wrote to be read.
 *    Amber on the skip branch, neutral on the import branch, exactly as the server colours it by
 *    choosing whether to push the same sentence into `problems`.
 * 3. THE DETAILS AND THE PROBLEMS. Every question superseded or retired, then every row that could
 *    not be read cleanly.
 *
 * The provenance paragraph is printed ONCE. The server deliberately duplicates it into `problems`
 * so that a client rendering only that list still tells the designer; this client renders both, and
 * [qFormProblemsToShow] drops the copy rather than the block.
 */
@Composable
internal fun UploadReportPanel(
    report: QFormChangeReportDto,
    onDismiss: () -> Unit,
    /**
     * What the panel calls the operation it is reporting on.
     *
     * A PARAMETER RATHER THAN A LITERAL because this panel is now reached by two things. The reuse
     * route answers in the upload response's shape deliberately — key for key — so that one panel
     * renders both; the price of that is that the panel must not assert a file was read. "What the
     * upload did" over a server-side copy is a small lie in the loudest line on the panel, and it is
     * the same lie `qFormProvenanceNotice` had to grow a third branch to stop telling.
     */
    heading: String = "What the upload did",
) {
    val provenance = remember(report) { qFormProvenanceNotice(report) }
    val problems = remember(report) { qFormProblemsToShow(report) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            heading,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(qFormUploadSummary(report), color = MaterialTheme.field.body, fontSize = 12.sp)
        if (report.versionAfter > report.versionBefore) {
            Text(
                "The questionnaire moved from version ${report.versionBefore} to " +
                    "${report.versionAfter}. A sitting somebody is part-way through was recorded " +
                    "against the older one.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
        }

        provenance?.let { notice ->
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                notice.heading,
                color = if (notice.warn) MaterialTheme.field.warning else MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            notice.tally?.let {
                Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
            // VERBATIM. This paragraph is the server's, written for a designer to read, and a client
            // that summarised it would be summarising the only explanation of why their fieldwork is
            // not where they expected to find it.
            Text(
                notice.reason,
                color = if (notice.warn) MaterialTheme.field.warning else MaterialTheme.field.body,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        if (report.details.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                "Questions that were superseded or retired",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            report.details.forEach { detail ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    detail.before?.takeIf { it.isNotBlank() }?.let {
                        Text("“$it”", color = MaterialTheme.field.body, fontSize = 11.sp)
                    }
                    detail.after?.takeIf { it.isNotBlank() }?.let {
                        Text("became “$it”", color = MaterialTheme.field.body, fontSize = 11.sp)
                    }
                    Text(detail.reason, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        if (problems.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                "Rows the import could not read cleanly (${problems.size})",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Each one names the sheet and the row exactly as Excel's row gutter shows it — press " +
                    "Ctrl+G in the spreadsheet and type the number.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
            problems.forEach { problem ->
                Text(
                    qFormProblemLine(problem),
                    color = if (problem.severity == "error") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.field.warning
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        TextButton(onClick = onDismiss) { Text("Done", fontSize = 13.sp) }
    }
}

/** The 48dp touch floor, in one place so the three controls above cannot each pick their own. */
private fun Modifier.heightIn48(): Modifier = this.heightIn(min = 48.dp)
