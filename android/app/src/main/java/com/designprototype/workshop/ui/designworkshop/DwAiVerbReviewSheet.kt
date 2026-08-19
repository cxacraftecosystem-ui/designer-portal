package com.designprototype.workshop.ui.designworkshop

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

/**
 * WHAT THE MODEL PRODUCED, WHAT IT WAS PRODUCED FROM, AND THE ONE QUESTION: does this stand in your
 * name?
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * THERE IS NO COPY BUTTON, NO "USE THIS TEXT", NO "REPLACE MY PARAGRAPH", ON ANY VERB, AND THERE
 * MUST NEVER BE ONE. THIS IS THE SINGLE MOST LIKELY DEFECT IN THE WHOLE FEATURE.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It will feel broken, and on a handset it will feel broken TWICE OVER: the review sheet is sitting
 * directly on top of the very paragraph it corrected, the designer's thumb is already there, and
 * `insertText` — the function dictation uses to put spoken words into this document at the caret — is
 * one import away. It is not an oversight and the ease of it is not an argument.
 *
 *  · Plan §3 forbids any AI-produced value feeding a field that is compared across surfaces. A
 *    RICH_TEXT stage field IS compared across surfaces, and the same note through a phone and through
 *    the cloud legitimately differs for ever — so the first cross-surface divergence test to fail
 *    would be blamed on a bug that is actually the design.
 *  · The server cannot even EXPRESS the write. A `LayerWritePlan` may only name a table in
 *    `WRITABLE_TABLES`, and `DwStageEntry` is deliberately absent; `_writable_model` has no entry for
 *    it either, so a plan that somehow carried its name would still have nowhere to be applied. On
 *    the server the rule is true by construction; on a client it is true only by there being nothing
 *    to press.
 *  · **A clipboard button is a paste button with one extra keystroke.** The cross-surface argument
 *    does not count keystrokes. Neither does an Android share sheet, which is a clipboard button with
 *    an icon on it.
 *
 * And the alternative is one this repository actively prefers, in `ai_verbs.expand`'s own words: *"A
 * designer who wants those words in the field types them, at which point they are that designer's
 * sentences under that designer's name — which is a true statement, unlike anything a paste button
 * could produce."*
 *
 * `DwAiVerbReviewGuardTest` reads this file's SOURCE and fails if `onChange(`, `insertText(`,
 * `ClipboardManager`, `LocalClipboardManager` or `commit(` appears in it. That is deliberate: adding
 * a paste button should be a failing test rather than a helpful commit. The one `Intent` in this file
 * shares a SUBTITLE FILE, which is a separate document a player opens and never text bound for a
 * stage field — see [DwSubtitleFileOutcome].
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY A FULL DIALOG AND WHY IT IS NOT FOLLOWED BY A CONFIRM.
 *
 * The text has to be READ before it is signed for, and the 201 from a verb is the one moment the
 * words are on screen at all (`_finish_verb` passes `include_text=True`; a list deliberately does
 * not carry text, because a workshop can hold twenty-five interviews). That makes this dialog the
 * confirm. Stacking a second "are you sure" on top of it would be the trains-people-to-click failure
 * that every refusal in this feature is written against.
 *
 * ── AND WHY IT IS AN [AlertDialog] AND NOT A BOTTOM SHEET ────────────────────────────────────────
 *
 * A bottom sheet is dismissed by a downward drag, which is the same gesture as scrolling the passage
 * a designer is reading. On a phone, the sheet holding a decision somebody's name goes on must not
 * close on the gesture they use to read it. `onDismissRequest` here means "leave it for now" — the
 * layer stays listed and inert, which is a real and honest third answer — and the dialog says so on
 * a labelled button as well.
 */
@Composable
internal fun DwAiVerbReviewSheet(
    run: DwAiVerbRun,
    bridge: DwAiVerbBridge,
    /**
     * The passage this handset sent, used ONLY as a fallback for `layer.source.text`.
     *
     * The server sends the evidence back on every supplied-text layer and the server's copy is the
     * one the annexure will print, so a disagreement between the two must resolve towards the stored
     * one.
     */
    sentPassage: String?,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = run.layer

    var busy by remember(layer.id) { mutableStateOf<String?>(null) }
    var problem by remember(layer.id) { mutableStateOf<String?>(null) }
    var speakers by remember(layer.id) { mutableStateOf(false) }
    var savedNote by remember(layer.id) { mutableStateOf<String?>(null) }

    // Keyed on the LAYER and not on the dialog opening: a designer who runs a second verb without
    // closing this would otherwise read the first run's refusal under the second run's words.
    LaunchedEffect(layer.id) {
        busy = null
        problem = null
        speakers = false
        savedNote = null
    }

    val evidence = layer.source?.text?.takeIf { it.isNotBlank() } ?: sentPassage?.takeIf { it.isNotBlank() }
    val cues = layer.cues

    AlertDialog(
        // "Leave it for now". The layer stays on the server, unaccepted and inert, and nothing has
        // been written into any document — so an accidental dismissal costs the designer a second
        // look at a row that is still there, and never a decision they did not make.
        onDismissRequest = { if (busy == null) onClose() },
        title = { Text(dwLayerKindLabel(layer.kind), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Nothing has been put in any document yet. Read it, then decide whether it " +
                        "stands in your name.",
                    color = MaterialTheme.field.body,
                    fontSize = 13.sp,
                )

                if (layer.kind == "EXPANDED") {
                    /*
                      THE ONE KIND THAT INVENTS, WARNED ABOUT WHERE THE DECISION IS MADE.

                      This carries the substance of `report_ai_layers.EXPANDED_NOTE`, which the
                      annexure prints under this heading and under no other — so the caution a
                      ministry officer will read a year from now is the caution the designer read
                      before signing. Two accounts of one risk, one of them arriving too late to act
                      on, is exactly what this feature exists to prevent.
                    */
                    DwVerbCaution(
                        "This passage was written by a machine from your short note, which is " +
                            "quoted below as its source. Anything in it that is not in that note — " +
                            "a detail, a reason, a connection between two things — was supplied by " +
                            "the model and was not recorded in the field. Treat the note as the " +
                            "record and this as a reading of it, and check any specific claim " +
                            "against the workshop's own material before quoting it."
                    )
                }

                dwLayerKindNote(layer.kind)?.let {
                    Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                }

                /*
                  WHAT WAS SENT, VERBATIM AND ABOVE THE OUTPUT, because accepting is a statement that
                  somebody checked one against the other. `layer_payload` calls this "the evidence
                  travels with the layer", and for a supplied-text source it is the only copy there is.
                */
                if (evidence != null) {
                    DwVerbSection("What was sent") {
                        DwVerbPassage(evidence)
                    }
                } else if (layer.source?.kind == "MEDIA") {
                    Text(
                        "Made from a file attached to this workshop. Check the sentence against the " +
                            "photograph or the recording itself, which is the evidence it stands on " +
                            "— it is on the stage this file is attached to.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                    )
                } else if (layer.source?.kind == "LAYER") {
                    Text(
                        "Made from another layer of this workshop.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                    )
                }

                DwVerbSection("What came back") {
                    val body = layer.text?.trim().orEmpty()
                    when {
                        // WITHHELD IS ITS OWN SENTENCE AND NOT AN EMPTY BOX. `textWithheld` is on
                        // every payload precisely so a client renders "you may not read this one"
                        // from a stated fact rather than inferring it from an absence — and the two
                        // look identical in a text box.
                        layer.textWithheld -> Text(
                            "You cannot read the recording this layer was made from, so its words " +
                                "are not shown here and you cannot accept it. Ask whoever uploaded " +
                                "the recording for access to their media, or ask them to accept it " +
                                "themselves.",
                            color = MaterialTheme.field.warning,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )

                        body.isNotEmpty() -> DwVerbPassage(body)

                        else -> Text(
                            "This layer carries no prose of its own — its content is the cue list " +
                                "below.",
                            color = MaterialTheme.field.muted,
                            fontSize = 12.sp,
                        )
                    }
                }

                layer.selfReportedConfidence?.let { confidence ->
                    /*
                      NEVER A BARE PERCENTAGE. `ai_verbs.caption` stores `confidenceIsCalibrated:
                      false` deliberately, because nothing in this repository has ever calibrated a
                      model's confidence against anything — and a number beside a caption is read as
                      a measurement of correctness. It travels so a designer deciding can see it, and
                      it travels labelled so nobody builds a gate on it.
                    */
                    Text(
                        "The model reported $confidence as its own confidence in this description. " +
                            "That is the model's own estimate, which nothing has checked against " +
                            "anything — it is not a measurement of whether the sentence is right. " +
                            "The photograph is.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                if (cues != null) {
                    DwVerbCueList(
                        cues = cues,
                        speakers = speakers,
                        onSpeakers = { speakers = it },
                        busy = busy != null,
                        onDownload = { format ->
                            busy = "DOWNLOAD"
                            problem = null
                            savedNote = null
                            scope.launch {
                                when (val answer = bridge.subtitleFile(layer.id, format, speakers)) {
                                    is DwSubtitleFileOutcome.Saved -> {
                                        savedNote = "Saved as ${answer.fileName}."
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = answer.mimeType
                                            putExtra(Intent.EXTRA_STREAM, answer.shareUri)
                                            // Without this the receiving app gets a Uri it has no
                                            // permission to read, and the share silently produces an
                                            // empty attachment.
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(send, "Open the subtitles")
                                            )
                                        }.onFailure {
                                            // A handset with nothing that opens a .srt is an
                                            // ordinary field phone. The file IS saved, so the
                                            // failure to hand it on is not a failure to produce it,
                                            // and the sentence must not say otherwise.
                                            savedNote = "Saved as ${answer.fileName}. Nothing on " +
                                                "this phone offered to open it, so it is waiting " +
                                                "in the workshop's files."
                                        }
                                    }

                                    is DwSubtitleFileOutcome.Refused -> problem = answer.sentence
                                    DwSubtitleFileOutcome.Offline -> problem = DW_VERBS_NEED_A_CONNECTION
                                }
                                busy = null
                            }
                        },
                    )
                }

                DwVerbProvenance(layer)

                /*
                  THE RUNNING ALLOWANCE, FROM THE 201'S OWN NUMBERS rather than from a second request,
                  and drawn only where there IS a ceiling — [dwAiVerbCountdown] returns null on an
                  uncapped deployment, because "0 left" must never be how "no ceiling" looks.
                */
                dwAiVerbCountdown(run.allowance.remaining, run.allowance.day)?.let {
                    Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
                }

                savedNote?.let {
                    Text(
                        it,
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                problem?.let {
                    Text(
                        it,
                        color = MaterialTheme.field.warning,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        // Announced: the button that caused it is several rows below the sentence.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = busy == null && !layer.textWithheld,
                onClick = {
                    busy = "ACCEPT"
                    problem = null
                    scope.launch {
                        when (val answer = bridge.accept(layer.id)) {
                            is DwAiLayerDecisionOutcome.Done -> onAccepted()
                            is DwAiLayerDecisionOutcome.Refused -> problem = answer.sentence
                            DwAiLayerDecisionOutcome.Offline -> problem = DW_VERBS_NEED_A_CONNECTION
                        }
                        busy = null
                    }
                },
            ) {
                if (busy == "ACCEPT") {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                // WORDED AND NOT ONLY SPUN, and worded as what the designer is actually saying. A
                // button labelled "Accept" is a form control; this is somebody's name going next to a
                // machine's sentence in a document a ministry officer reads.
                Text(if (busy == "ACCEPT") "Accepting…" else "I have read it — accept it in my name")
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = busy == null, onClick = onClose) {
                    Text("Leave it for now")
                }
                TextButton(
                    enabled = busy == null,
                    onClick = {
                        busy = "DECLINE"
                        problem = null
                        scope.launch {
                            when (val answer = bridge.decline(layer.id)) {
                                is DwAiLayerDecisionOutcome.Done -> onDeclined()
                                is DwAiLayerDecisionOutcome.Refused -> problem = answer.sentence
                                DwAiLayerDecisionOutcome.Offline -> problem = DW_VERBS_NEED_A_CONNECTION
                            }
                            busy = null
                        }
                    },
                ) {
                    Text(
                        if (busy == "DECLINE") "Declining…" else "Decline it",
                        color = MaterialTheme.field.warning,
                    )
                }
            }
        },
    )
}

/** A warning block that reads as a caution rather than as an error — this is not a failure. */
@Composable
private fun DwVerbCaution(sentence: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                sentence,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun DwVerbSection(heading: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(heading, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.field.body)
        content()
    }
}

/**
 * A passage, bounded and scrollable, and NEVER selectable.
 *
 * `SelectionContainer` is what the rest of this app would reach for, and it is deliberately absent:
 * a long-press on model prose raises Android's own Copy action, which is the paste button this file's
 * header refuses, supplied by the platform. The designer reads it here and, if they want those words
 * in the field, types them — at which point they are that designer's sentences under that designer's
 * name.
 */
@Composable
private fun DwVerbPassage(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = MaterialTheme.field.body,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

/** How many cues are drawn before the list says it stopped. A phone is not a subtitle editor. */
private const val DW_CUE_PREVIEW_LIMIT: Int = 25

@Composable
private fun DwVerbCueList(
    cues: DwSubtitleCues,
    speakers: Boolean,
    onSpeakers: (Boolean) -> Unit,
    busy: Boolean,
    onDownload: (DwSubtitleFormat) -> Unit,
) {
    DwVerbSection("The cues") {
        Text(
            buildString {
                append(if (cues.count == 1) "1 cue" else "${cues.count} cues")
                if (cues.estimatedCues > 0) append(", ${cues.estimatedCues} of them approximate")
                cues.durationSeconds?.let { append(" · ${dwSubtitleTimecode(it)}") }
                cues.language?.let { append(" · $it") }
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )

        if (cues.cues.isEmpty()) {
            Text(
                "This layer holds no readable cue list.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cues.cues.take(DW_CUE_PREVIEW_LIMIT).forEach { cue ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                "${dwSubtitleTimecode(cue.start)} → ${dwSubtitleTimecode(cue.end)}",
                                color = MaterialTheme.field.muted,
                                fontSize = 11.sp,
                            )
                            cue.speaker?.let {
                                Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
                            }
                            if (cue.estimated) {
                                Text(
                                    "approximate timing",
                                    color = MaterialTheme.field.warning,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Text(cue.text, color = MaterialTheme.field.body, fontSize = 12.sp)
                    }
                }
            }
            /*
              A LIST THAT QUIETLY STOPS IS INDISTINGUISHABLE FROM A SHORT LIST — the most repeated
              defect class in this repository, and worse on a phone, where the bottom of a scroll
              region is off-screen by default. The file below carries every cue.
            */
            if (cues.cues.size > DW_CUE_PREVIEW_LIMIT) {
                Text(
                    "The first $DW_CUE_PREVIEW_LIMIT cues are shown. The file below carries all " +
                        "${cues.count}.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        if (cues.hasSpeakers) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(checked = speakers, onCheckedChange = onSpeakers, enabled = !busy)
                Text(
                    "Put the speaker label in front of each line. THE LABELS ARE THE ENGINE'S OWN " +
                        "GUESS — nobody told it how many people were in the room or who they were, " +
                        "and it can merge two quiet voices or split one person who moved away from " +
                        "the microphone. The .vtt carries that caution inside the file; SubRip has " +
                        "no comment syntax and cannot, so a .srt carries the labels alone.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        DwSubtitleFormat.entries.forEach { format ->
            OutlinedButton(
                onClick = { onDownload(format) },
                enabled = !busy && cues.cues.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(format.label, fontSize = 12.sp)
            }
        }
        Text(
            "You can save these and watch them against the recording before deciding. Saving changes " +
                "nothing and does not accept anything.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/**
 * THE PROVENANCE, ALWAYS, IN WORDS AND NEVER A NUMERAL.
 *
 * "not recorded" is a real answer and is drawn in the same quiet type as a recorded one — a row whose
 * provider nobody stored is the ordinary case, and a warning colour would tell a designer their
 * archive is broken when it is not.
 */
@Composable
private fun DwVerbProvenance(layer: DwAiLayerView) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                dwTierLabel(layer.tier),
                color = MaterialTheme.field.body,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                dwTierSentence(layer.tier),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            HorizontalDivider()
            Text(
                "Provider: ${dwProvenanceValue(layer.provider)} · " +
                    "Model: ${dwModelDescription(layer.modelId, layer.modelVersion)} · " +
                    "Language: ${dwLanguageDescription(layer.language)}",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // BOTH LANGUAGES, ON A TRANSLATION AND ONLY THERE. "In English" is not a provenance
            // record for a translation — a reader checking it against what the artisan said has to
            // know what they said it in — so `ai_layers._check_languages` refuses a translation
            // missing either, and this prints the pair it insists on.
            if (layer.kind == "TRANSLATION") {
                Text(
                    "From ${dwLanguageDescription(layer.sourceLanguage)} into " +
                        "${dwLanguageDescription(layer.targetLanguage)}.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (layer.kind == "SUBTITLES") {
                // THE ONE VERB THAT NEVER RUNS ON THE DESIGNER'S OWN KEY, said beside the provenance
                // line that would otherwise imply it might have.
                Text(
                    DW_SUBTITLES_DEPLOYMENT_KEY_NOTE,
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            Text(
                "Accepting records your name and the moment against this " +
                    "${dwLayerKindNoun(layer.kind)}. Until somebody does, it sits with this " +
                    "workshop and no report will print it.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// The vocabulary a reader meets — the same words the annexure prints
// -------------------------------------------------------------------------------------------------

/**
 * What each rung IS, in the words a designer would use, and the same words the report annexure
 * prints under the passage — so the person signing recognises what they signed for when they meet it
 * again in the .docx a year later.
 *
 * "Machine transcript" rather than the bare "Transcript" for RAW_TRANSCRIPT: a workshop also holds
 * transcripts a person typed or corrected, and a heading that did not distinguish them would let
 * model output be read as somebody's own words.
 */
private val DW_LAYER_KIND_LABELS: Map<String, String> = mapOf(
    "RAW_TRANSCRIPT" to "Machine transcript",
    "CLEANED_TRANSCRIPT" to "AI-cleaned transcript",
    "SUMMARY" to "AI summary",
    "OCR_TEXT" to "Text read off a photograph",
    "STRUCTURED_TEXT" to "Fields read off a photograph",
    "TAGS" to "Suggested tags",
    "METADATA" to "Extracted details",
    "PROOFREAD" to "AI-corrected spelling and punctuation",
    "EXPANDED" to "Prose written by AI from a designer's note",
    "TRANSLATION" to "AI translation",
    "CAPTION" to "AI description of a photograph or video",
    "SUBTITLES" to "AI subtitles, with their timings",
)

/**
 * A kind's heading.
 *
 * A kind this build has never heard of degrades to an honest note carrying the SERVER'S OWN WORD,
 * never to a blank — a deployment can be a release behind, which the server itself allows for in
 * `_verb_layer_kind`. The row still shows its tier, its model and its acceptance, all of which are
 * readable without knowing what the kind means.
 */
fun dwLayerKindLabel(kind: String?): String {
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.let { return it }
    return if (!kind.isNullOrBlank()) {
        "A layer kind this screen does not know ($kind)"
    } else {
        "A layer with no kind recorded"
    }
}

/**
 * A kind as a NOUN PHRASE that can sit inside a sentence — "…against this AI translation."
 *
 * Separate from [dwLayerKindLabel] because that one degrades to a whole sentence, and a sentence
 * inside a sentence reads as a bug.
 */
fun dwLayerKindNoun(kind: String?): String =
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.lowercase() ?: "layer"

/**
 * The sentence under a kind's heading: what the machine WAS and WAS NOT allowed to change.
 *
 * That is the question somebody about to quote the passage actually has, and it is the reason each of
 * the five verbs has its own kind rather than reusing a neighbour's — each is a different PROMISE to
 * whoever reads the document.
 */
fun dwLayerKindNote(kind: String?): String? = when (kind) {
    "PROOFREAD" ->
        "Spelling, grammar and punctuation only. The model was refused permission to translate, to " +
            "restructure or to shorten, and it was given the craft vocabulary as a do-not-touch " +
            "list so that “dabu” is not “corrected” to “double”. The original is untouched and " +
            "stays beside this."

    "EXPANDED" ->
        "A machine wrote these sentences from a short note. It is the only kind here that INVENTS, " +
            "and nothing may be derived from an expansion."

    "TRANSLATION" ->
        "A translation that stands BESIDE the original rather than replacing it, so a reader who " +
            "wants the artisan's own words can still have them. The row records which language it " +
            "came from as well as which it went into, because a translated passage nobody can trace " +
            "back is a passage nobody can check."

    "CAPTION" ->
        "One sentence a model wrote about the photograph or video — for the media annexure, and for " +
            "a screen reader. Check it against the picture, which is the evidence it stands on."

    "SUBTITLES" ->
        "Timed captions: a cue list with a start and an end for every line. The timings are the " +
            "whole verb — a subtitle without them is a transcript."

    else -> null
}

/**
 * THE TIER, SAID AS WHAT IT MEANS, with no numeral.
 *
 * `AiTier.number` exists on the server "for prose only, never for a comparison", and a chip reading
 * "Tier 3" invites exactly the comparison the enum was chosen to prevent: Tier 1 is the only tier
 * that works in a courtyard with no signal and Tier 3 is the only one carrying the craft keyterm
 * list, so neither direction is "better".
 */
fun dwTierLabel(tier: String?): String = when (tier) {
    "TIER_1" -> "On the handset"
    "TIER_2" -> "On the handset, small model"
    "TIER_3" -> "In the cloud"
    null, "" -> "Tier not recorded"
    else -> "An unfamiliar tier ($tier)"
}

fun dwTierSentence(tier: String?): String = when (tier) {
    "TIER_1" ->
        "Produced by a model running on the device itself. This is the only tier that works in a " +
            "courtyard with no signal, and the recording never left the handset."

    "TIER_2" ->
        "Produced by a small language model running on the handset. Nothing left the device, and " +
            "what a given handset can run depends on the handset."

    "TIER_3" ->
        "Produced by a provider in the cloud. This is the only tier that carries the craft " +
            "vocabulary — the list that stops “dabu” being written as “double” — and the material " +
            "left the device to reach it."

    else ->
        "This server recorded a tier this screen does not know, so where the model ran cannot be " +
            "stated in words here. The stored value is shown as it was sent."
}

/**
 * `ai_layers.UNRECORDED` — the honest-unknown sentinel, rendered as words rather than as the token.
 *
 * It is a REAL stored value and not a null: the server writes it deliberately, because a null on a
 * provenance column would read as "there is no such thing" where the truth is "nobody recorded one".
 * A screen that printed the bare word would put a shouting constant in front of a designer.
 */
private const val DW_UNRECORDED_TOKEN: String = "UNRECORDED"

private fun dwProvenanceValue(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return if (value.isEmpty() || value == DW_UNRECORDED_TOKEN) "not recorded" else value
}

private fun dwModelDescription(modelId: String?, modelVersion: String?): String {
    val id = dwProvenanceValue(modelId)
    val version = modelVersion?.trim().orEmpty()
    return if (id == "not recorded" || version.isEmpty() || version == DW_UNRECORDED_TOKEN) {
        id
    } else {
        "$id · $version"
    }
}

/**
 * A language column in words.
 *
 * `multi` IS A REAL ANSWER AND NOT A PLACEHOLDER: Deepgram Nova-3 is deliberately called with
 * `language=multi` precisely because these interviews are code-switched mid-sentence. Printing the
 * bare token would read like a missing value, which is the opposite of what it says.
 */
private fun dwLanguageDescription(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return when {
        value.isEmpty() || value == DW_UNRECORDED_TOKEN -> "not recorded"
        value.equals("multi", ignoreCase = true) -> "multi — mixed, code-switched speech"
        else -> value
    }
}
