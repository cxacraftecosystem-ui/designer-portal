package com.designprototype.workshop.ui.designworkshop

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
import com.designprototype.workshop.data.DW_SUBTITLES_DEPLOYMENT_KEY_NOTE
import com.designprototype.workshop.data.DwAiLayerDto
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwSubtitleFormat
import com.designprototype.workshop.data.DwSubtitleSummary
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwSubtitleCueSummary
import com.designprototype.workshop.data.dwSubtitleTimecode
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
 * It will feel broken, and on a handset it will feel broken twice over: this sheet is sitting
 * directly on top of the paragraph it corrected, the designer's thumb is already there, and
 * `insertText` — the very function dictation uses to put spoken words into this document at the
 * caret — is one import away. The ease of it is not an argument.
 *
 *  · Plan §3 forbids any AI-produced value feeding a field that is compared across surfaces. A
 *    RICH_TEXT stage field IS compared across surfaces, and the same note through a phone and through
 *    the cloud legitimately differs for ever — so the first cross-surface divergence test to fail
 *    would be blamed on a bug that is actually the design.
 *  · The server cannot even EXPRESS the write. A `LayerWritePlan` may only name a table in
 *    `WRITABLE_TABLES`, `DwStageEntry` is deliberately absent, and `_writable_model` has no entry for
 *    it either — so a plan that somehow carried its name would still have nowhere to be applied. On
 *    the server the rule is true by construction; on a client it is true only by there being nothing
 *    to press.
 *  · **A clipboard button is a paste button with one extra keystroke.** The cross-surface argument
 *    does not count keystrokes, and an Android share sheet is a clipboard button with an icon on it.
 *
 * The alternative is one this repository actively prefers, in `ai_verbs.expand`'s own words: *"A
 * designer who wants those words in the field types them, at which point they are that designer's
 * sentences under that designer's name — which is a true statement, unlike anything a paste button
 * could produce."*
 *
 * `DwAiVerbSurfaceGuardTest` reads this file's SOURCE and fails if it gains a call to the editor's
 * own change handler, to the dictation insert, to the system clipboard, or to a share intent — the
 * four ways a paste button gets built. That test carries the list and the argument for each; the list
 * is not repeated here, because a comment naming the exact tokens would itself trip the guard. Adding
 * a paste button should be a failing test rather than a helpful commit.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY A FULL DIALOG, AND WHY IT IS NOT FOLLOWED BY A CONFIRM.
 *
 * The text has to be READ before it is signed for, and a verb's 201 is the one moment the words are
 * on screen at all — `_finish_verb` passes `include_text=True`, while a list deliberately does not
 * carry text because a workshop can hold twenty-five interviews. That makes this dialog the confirm.
 * Stacking a second "are you sure" on top of it would be the trains-people-to-click failure every
 * refusal in this feature is written against.
 *
 * ── AND WHY IT IS AN [AlertDialog] AND NOT A BOTTOM SHEET ───────────────────────────────────────
 *
 * A bottom sheet is dismissed by a downward drag, which is the same gesture as scrolling the passage
 * a designer is reading. The surface holding a decision somebody's name goes on must not close on the
 * gesture they use to read it. Dismissing here means "leave it for now" — the layer stays on the
 * server, unaccepted and inert, which is a real third answer and is on a labelled button as well.
 */
@Composable
internal fun DwAiVerbReviewSheet(
    result: DwAiVerbResultDto,
    repository: WorkshopRepository,
    /** The server's id for this workshop. Never the route param — see [DwAiVerbSurface]. */
    serverWorkshopId: String,
    /**
     * The passage this phone sent, used ONLY as a fallback for `layer.source.text`.
     *
     * The server sends the evidence back on every supplied-text layer and the server's copy is the
     * one the annexure will print, so a disagreement between the two resolves towards the stored one.
     */
    sentPassage: String?,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = result.layer

    // Keyed on the LAYER and not on the dialog opening: a designer who runs a second verb without
    // closing this would otherwise read the first run's refusal under the second run's words.
    var busy by remember(layer.id) { mutableStateOf<String?>(null) }
    var problem by remember(layer.id) { mutableStateOf<String?>(null) }
    var speakers by remember(layer.id) { mutableStateOf(false) }
    var savedTo by remember(layer.id) { mutableStateOf<String?>(null) }

    val evidence = layer.source?.text?.takeIf { it.isNotBlank() }
        ?: sentPassage?.takeIf { it.isNotBlank() }
    val cues: DwSubtitleSummary? = remember(layer.id, layer.kind) {
        if (layer.kind == "SUBTITLES") dwSubtitleCueSummary(layer.payload) else null
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onClose() },
        title = { Text(dwLayerKindLabel(layer.kind), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                /*
                  RULE 3, ON SCREEN, AT THE MOMENT IT MATTERS MOST — and read off the wire rather than
                  assumed. `_finish_verb` puts `accepted: false` and `acceptanceRequired: true` in the
                  body precisely because *"the client that just asked for this has words on screen and
                  is one tap from putting them in a report"*. A screen that hid them would be hiding
                  the rule.
                */
                Text(
                    if (result.acceptanceRequired && !result.accepted) {
                        "Nothing has been put in any document yet. Read it, then decide whether it " +
                            "stands in your name."
                    } else {
                        // Unreachable through these five routes today; drawn from the flags rather
                        // than from a constant so that a server which one day answers differently is
                        // reported rather than contradicted.
                        "This layer's acceptance state came back as the server recorded it."
                    },
                    color = MaterialTheme.field.body,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                if (layer.kind == "EXPANDED") {
                    /*
                      THE ONE KIND THAT INVENTS, WARNED ABOUT WHERE THE DECISION IS MADE.

                      This carries the substance of `report_ai_layers.EXPANDED_NOTE`, which the
                      annexure prints under this heading and under no other — so the caution a
                      ministry officer reads a year from now is the caution the designer read before
                      signing. Two accounts of one risk, one of them arriving too late to act on, is
                      exactly what this feature exists to prevent.
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
                  travels with the layer", and for a supplied-text source it is the only copy there is
                  — there is no second request that could fetch it.
                */
                if (evidence != null) {
                    DwVerbSection("What was sent") { DwVerbPassage(evidence) }
                } else if (layer.source?.kind == "MEDIA") {
                    Text(
                        "Made from a file attached to this workshop. Check the sentence against the " +
                            "photograph or the recording itself, which is the evidence it stands on " +
                            "— it is on the stage this file is attached to.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
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
                        /*
                          WITHHELD IS ITS OWN SENTENCE AND NOT AN EMPTY BOX. `textWithheld` is on
                          every payload precisely so a client says "you may not read this one" from a
                          stated fact rather than inferring it from an absence — and an empty box and
                          a withheld one look identical. Who may read a recording is decided per file
                          and not by who may open the workshop, so this is reachable by an ordinary
                          colleague with a viewer grant.
                        */
                        layer.textWithheld -> Text(
                            "You cannot read the recording this layer was made from, so its words " +
                                "are not shown here and you cannot accept it: an acceptance says a " +
                                "person read this text and stands behind it, and the report prints " +
                                "their name beside it. Ask whoever uploaded the recording for " +
                                "access to their media, or ask them to accept it themselves.",
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

                if (cues != null) {
                    DwVerbCueList(
                        cues = cues,
                        speakers = speakers,
                        onSpeakers = { speakers = it },
                        busy = busy != null,
                        onSave = { format ->
                            busy = "SAVE"
                            problem = null
                            savedTo = null
                            scope.launch {
                                runCatching {
                                    repository.downloadDesignWorkshopSubtitles(
                                        context = context,
                                        workshopId = serverWorkshopId,
                                        layerId = layer.id,
                                        format = format,
                                        speakers = speakers,
                                    )
                                }.onSuccess { savedTo = it }
                                    .onFailure { problem = dwAiVerbProblem(it) }
                                busy = null
                            }
                        },
                    )
                }

                DwVerbProvenance(layer)

                /*
                  THE RUNNING ALLOWANCE, FROM THE 201'S OWN NUMBERS rather than from a second request,
                  and only where there IS a ceiling — [dwAiVerbCountdownLine] answers null on an
                  uncapped deployment, because "0 left" must never be how "no ceiling" looks.
                */
                dwAiVerbCountdownLine(result.aiVerbsRemaining, result.aiVerbDay)?.let {
                    Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
                }

                savedTo?.let {
                    Text(
                        // THE PATH THE FILE ACTUALLY LANDED AT, which is the repository's answer and
                        // not a guess: on a pre-Q handset that refused the storage permission it is
                        // app-private storage rather than Downloads, and a sentence that named
                        // Downloads either way would send a designer looking in the wrong folder.
                        "Saved to $it. Play it against the recording before deciding — saving " +
                            "changes nothing and accepts nothing.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                problem?.let {
                    Text(
                        it,
                        color = MaterialTheme.field.warning,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        // Announced: the button that caused it is several rows above the sentence,
                        // and on a phone it is routinely below the fold.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                // REFUSED FOR A WITHHELD LAYER, matching `accept_ai_layer`, which refuses the same
                // account with the same argument: *"a signature on a page the signer is not allowed
                // to open is worth less than no signature, because the report then names them as the
                // person who checked it."* Refused here as well so the round trip is not spent
                // learning it, and the sentence above the button is what says why.
                enabled = busy == null && !layer.textWithheld,
                onClick = {
                    busy = "ACCEPT"
                    problem = null
                    scope.launch {
                        runCatching {
                            repository.acceptDesignWorkshopAiLayer(serverWorkshopId, layer.id)
                        }.onSuccess { onAccepted() }
                            .onFailure { problem = dwAiVerbProblem(it) }
                        busy = null
                    }
                },
            ) {
                if (busy == "ACCEPT") {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                // WORDED AND NOT ONLY SPUN, and worded as what the designer is actually saying. A
                // button labelled "Accept" is a form control; this is somebody's name going next to a
                // machine's sentence in a document a ministry officer reads.
                Text(if (busy == "ACCEPT") "Accepting…" else "I have read it — accept it in my name")
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(enabled = busy == null, onClick = onClose) {
                    // THE HONEST THIRD ANSWER. The layer stays on the server, listed and inert, and
                    // no report reads it — so leaving is not the same as declining and must not be
                    // worded as though it were.
                    Text("Leave it for now")
                }
                TextButton(
                    enabled = busy == null,
                    onClick = {
                        busy = "DECLINE"
                        problem = null
                        scope.launch {
                            runCatching {
                                repository.declineDesignWorkshopAiLayer(serverWorkshopId, layer.id)
                            }.onSuccess { onDeclined() }
                                .onFailure { problem = dwAiVerbProblem(it) }
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

/** A caution block. It reads as a warning about content and never as a failure of the app. */
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
        Text(
            heading,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.field.body,
        )
        content()
    }
}

/**
 * A passage, bounded and scrollable, and **deliberately not inside a selection container**.
 *
 * Wrapping it would raise Android's own Copy action on a long press — the paste button this file's
 * header refuses, supplied by the platform rather than by us, which makes it no less a paste button.
 * The designer reads it here and, if they want those words in the field, types them; at which point
 * they are that designer's sentences under that designer's name.
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
    cues: DwSubtitleSummary,
    speakers: Boolean,
    onSpeakers: (Boolean) -> Unit,
    busy: Boolean,
    onSave: (DwSubtitleFormat) -> Unit,
) {
    DwVerbSection("The cues") {
        if (!cues.readable) {
            // NOT THE SAME SENTENCE AS "no cues". `dwSubtitleCueSummary` reports `readable = false`
            // for a payload that is not a cue list at all, which is a different fact from a cue list
            // with nothing in it, and only one of the two is something a designer can act on.
            Text(
                "This layer's stored cue list is not in a shape this build can read, so the cues " +
                    "cannot be shown here. The file below is built by the server from the same rows " +
                    "and is still worth saving.",
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
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

        if (cues.cues.isNotEmpty()) {
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
              defect class in this repository, and worse on a phone, where the end of a scroll region
              is off-screen by default. The saved file carries every cue and this says so.
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
                    "Put the speaker label in front of each line. The labels are the engine's own " +
                        "guess — nobody told it how many people were in the room or who they were, " +
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
                onClick = { onSave(format) },
                // Offered even when this build could not parse the payload, because the FILE is built
                // by the server from its own rows and does not depend on this parse. Withheld only
                // when there is provably nothing: a readable list with no cues in it.
                enabled = !busy && (cues.cues.isNotEmpty() || !cues.readable),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(format.label, fontSize = 12.sp)
            }
        }
        /*
          SAVING IS NOT GATED ON ACCEPTANCE, matching the route, which is deliberate and says so:
          *"requiring acceptance first would mean accepting subtitles nobody has watched, which is the
          opposite of what acceptance is for."* This is the designer looking at what the model
          produced, in the only form in which subtitles can actually be judged — played against the
          video.
        */
        Text(
            "You can save these and watch them against the recording before deciding.",
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
private fun DwVerbProvenance(layer: DwAiLayerDto) {
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
                "Provider: ${dwProvenanceWord(layer.provider)} · " +
                    "Model: ${dwModelWords(layer.modelId, layer.modelVersion)} · " +
                    "Language: ${dwLanguageWords(layer.language)}",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            /*
              BOTH LANGUAGES, ON A TRANSLATION AND ONLY THERE. "In English" is not a provenance record
              for a translation — a reader checking it against what the artisan said has to know what
              they said it in — which is why `ai_layers._check_languages` refuses a translation
              missing either. The pair is read off the columns the payload sends unconditionally,
              rather than out of the text.
            */
            if (layer.kind == "TRANSLATION") {
                Text(
                    "From ${dwLanguageWords(layer.sourceLanguage)} into " +
                        dwLanguageWords(layer.targetLanguage) + ".",
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
                    "${dwLayerKindNoun(layer.kind)}. Until somebody does, it stays with this " +
                    "workshop and no report will print it.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
