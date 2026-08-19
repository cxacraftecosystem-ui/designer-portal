package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

/**
 * DESCRIBE THIS PHOTOGRAPH, OR MAKE SUBTITLES FOR THIS RECORDING — under the tile, on the stage the
 * file was attached to.
 *
 * ── WHY HERE AND NOT ON A SCREEN OF ITS OWN ─────────────────────────────────────────────────────
 *
 * Caption and subtitles are about MEDIA rather than about prose, so they belong where the media is:
 * the designer is looking at the thing being described, which is the evidence the sentence has to be
 * checked against. The alternative — a batch surface listing every photograph in the workshop — was
 * rejected for a concrete reason and not a stylistic one: **there is no endpoint that lists a
 * workshop's images and videos with their stage and field labels.** `GET /{id}/transcripts` is
 * AUDIO-only (`load_transcript_items` walks `audio_references`), so a batch screen would need a new
 * backend route. The tile needs none.
 *
 * ── EACH FILE IS OFFERED ONLY THE VERB ITS TYPE ADMITS ──────────────────────────────────────────
 *
 * [dwVerbsForMediaType] mirrors `_VERB_MEDIA_TYPES` exactly, so this client never produces the 409
 * that ends *"Choose another file — nothing was sent anywhere and nothing was spent."* The server
 * checks it before any bytes move precisely because the failure otherwise is expensive and
 * unreadable: a caption run over an audio file uploads a recording to a vision model, which answers
 * with a parse error after the credit is spent.
 *
 * ── **NOTHING HERE WRITES A CAPTION INTO THE FIELD'S OWN CAPTION BOX** ──────────────────────────
 *
 * The registry carries `caption_for` fields and this handset draws them, in `FieldRenderer`'s own
 * words, "INSIDE this field's block, directly under the media it describes" — so the box is right
 * there and the temptation is obvious. It is refused for the reason written out in full in
 * [DwAiVerbReviewSheet]'s header: a caption written into `caption_for` is an AI value in a field
 * compared across surfaces, which plan §3 forbids and which the server cannot even express
 * (`DwStageEntry` is absent from `WRITABLE_TABLES`). The server's own consent refusal for CAPTION
 * already names the honest alternative — *"Write the description yourself in the caption box under
 * the photograph, where the stage has one"* — and that is a designer's sentence under a designer's
 * name, which is a true statement no paste button could produce.
 *
 * ── TWO SEPARATE "NOT ON THE SERVER YET" STATES, AND THEY ARE NOT INTERCHANGEABLE ───────────────
 *
 * [DW_MEDIA_NOT_UPLOADED_YET] is about ONE FILE: a descriptor with no `remoteMediaId` has no server
 * id to send, so there is nothing to work on even on a perfect connection, and the other tiles of
 * the same field are unaffected. [DW_WORKSHOP_NOT_ON_SERVER_YET] is about the WHOLE WORKSHOP. The
 * web shipped with the second missing, offering both verbs on every tile of every unsynced workshop
 * into a bare "Record not found"; the ladder here gets the workshop rung from [dwVerbWorkshopGate],
 * which every surface shares, so the two cannot come apart.
 */
@Composable
internal fun DwMediaAiVerbsRow(
    bridge: DwAiVerbBridge,
    item: DwMediaItem,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val verbs = remember(item.mediaType) { dwVerbsForMediaType(item.mediaType) }
    if (verbs.isEmpty()) return

    val scope = rememberCoroutineScope()
    var running by remember(item.id) { mutableStateOf<DwAiVerb?>(null) }
    var problem by remember(item.id) { mutableStateOf<String?>(null) }
    var result by remember(item.id) { mutableStateOf<DwAiVerbRun?>(null) }

    /**
     * Which server media ids already carry a live SUBTITLES layer.
     *
     * **NULL IS "NOT KNOWN" AND IS NEVER FLATTENED TO AN EMPTY SET.** An empty set would say "this
     * recording has not been subtitled", which is a confident wrong answer that invites a designer to
     * spend a paid SECOND upload of audio they have already paid to upload once — the cost
     * [DW_SUBTITLES_SECOND_UPLOAD_NOTE] exists to warn about. A read that fails stays null and the
     * control says nothing either way, which is the honest shape of not having been told.
     */
    var subtitled by remember(bridge.serverWorkshopId) { mutableStateOf<Set<String>?>(null) }

    /*
      CONNECTIVITY READ ONCE PER TILE COMPOSITION, and again at the press. A media field can hold a
      dozen photographs and a read per recomposition would be a dozen `getSystemService` calls per
      frame of the list they sit in.
    */
    var online by remember { mutableStateOf(true) }
    LaunchedEffect(item.id) {
        online = bridge.isOnline()
        if (DwAiVerb.SUBTITLES in verbs) subtitled = bridge.subtitledMediaIds()
    }

    val gate = dwVerbWorkshopGate(bridge.serverWorkshopId, bridge.consent, online)
    val meter = DwAiVerbMeter.current
    val ceiling = dwVerbAllowanceRefusal(meter?.remaining, DwAiVerbMeter.refusal)
    val serverMediaId = item.remoteMediaId?.takeIf { it.isNotBlank() }

    fun start(verb: DwAiVerb) {
        problem = null
        val workshop = bridge.serverWorkshopId
        if (workshop.isNullOrBlank()) {
            problem = DW_WORKSHOP_NOT_ON_SERVER_YET
            return
        }
        // RE-CHECKED AT THE PRESS. A file uploads while its tile is on screen — this is precisely the
        // moment `remoteMediaId` is written — so the id read at composition can be a fact that has
        // changed, in the direction that makes the verb possible rather than impossible.
        val mediaId = item.remoteMediaId?.takeIf { it.isNotBlank() }
        if (mediaId == null) {
            problem = DW_MEDIA_NOT_UPLOADED_YET
            return
        }
        if (!bridge.isOnline()) {
            online = false
            problem = DW_VERBS_NEED_A_CONNECTION
            return
        }
        running = verb
        scope.launch {
            val outcome = when (verb) {
                // `language` is deliberately not asked for on this surface. A caption in the model's
                // own language is a perfectly good answer, `multi` is dropped by the server rather
                // than refused, and asking a designer to name a language for a sentence nobody has
                // written yet is asking them to assert a fact about output that does not exist.
                DwAiVerb.CAPTION -> bridge.caption(mediaId, null)
                DwAiVerb.SUBTITLES -> bridge.subtitles(mediaId)
                DwAiVerb.PROOFREAD, DwAiVerb.EXPAND, DwAiVerb.TRANSLATE -> null
            }
            when (outcome) {
                is DwAiVerbOutcome.Produced -> {
                    result = outcome.run
                    DwAiVerbMeter.learn(outcome.run.allowance, refusal = null)
                    // A SUBTITLES run that landed means this recording now has one, whatever the read
                    // above did or did not answer. Recorded so a second tap does not buy a second
                    // upload of the same audio while this screen is still open.
                    if (verb == DwAiVerb.SUBTITLES) {
                        subtitled = (subtitled ?: emptySet()) + mediaId
                    }
                }

                is DwAiVerbOutcome.Refused -> {
                    problem = outcome.sentence
                    outcome.allowance?.let { DwAiVerbMeter.learn(it, refusal = outcome.sentence) }
                }

                DwAiVerbOutcome.Offline -> {
                    online = false
                    problem = DW_VERBS_NEED_A_CONNECTION
                }

                null -> problem = DW_MEDIA_NOT_UPLOADED_YET
            }
            running = null
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            running != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    if (running == DwAiVerb.SUBTITLES) {
                        "Sending this recording up for subtitling…"
                    } else {
                        "Asking a model to describe this…"
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                )
            }

            // WAITING IS SILENT AND INERT — nothing is drawn while the subtitle read is in flight.
            // The floor answer is "not known", and a control drawn against it would either offer a
            // second paid upload or withhold a first one, both without a fact behind it.
            gate is DwVerbGate.Waiting -> Unit

            gate is DwVerbGate.Refused -> DwMediaVerbNote(gate.sentence)

            serverMediaId == null -> DwMediaVerbNote(DW_MEDIA_NOT_UPLOADED_YET)

            ceiling != null -> DwMediaVerbNote(ceiling)

            else -> {
                if (DwAiVerb.CAPTION in verbs) {
                    OutlinedButton(
                        onClick = { start(DwAiVerb.CAPTION) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.ImageSearch,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Describe this for the annexure", fontSize = 13.sp)
                    }
                }
                if (DwAiVerb.SUBTITLES in verbs) {
                    val already = subtitled?.contains(serverMediaId) == true
                    if (already) {
                        DwMediaVerbNote(
                            "This recording already has AI subtitles on this workshop, so making " +
                                "them again would send the audio up a second time and spend another " +
                                "run. Open them from the workshop's AI layers rather than repeating " +
                                "the upload."
                        )
                    } else {
                        OutlinedButton(
                            onClick = { start(DwAiVerb.SUBTITLES) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Filled.ClosedCaption,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Make subtitles", fontSize = 13.sp)
                        }
                        /*
                          SAID BEFORE THE PRESS AND NOT AFTER IT. This is the one verb that costs an
                          upload of bytes the archive already holds, and on a handset that upload
                          comes out of the designer's own mobile data. The route's own docstring calls
                          it "a defect rather than a design": every timing this system has ever
                          received was discarded one line after being parsed.
                        */
                        Text(
                            DW_SUBTITLES_SECOND_UPLOAD_NOTE,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        // AND THE READ THAT WOULD HAVE ANSWERED "already done" NEVER LANDED. Silence
                        // here would let a designer spend that upload twice with nothing on screen
                        // admitting the app could not check.
                        if (subtitled == null) {
                            Text(
                                "This phone could not check whether these subtitles already exist, " +
                                    "so it cannot tell you either way.",
                                color = MaterialTheme.field.muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
                dwAiVerbCountdown(meter?.remaining, meter?.day)?.let {
                    Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        problem?.let {
            Text(
                it,
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    result?.let { run ->
        DwAiVerbReviewSheet(
            run = run,
            bridge = bridge,
            // A media verb sends no passage — the evidence is the file itself, which is on screen
            // directly above this row, and the sheet says so rather than quoting words nobody sent.
            sentPassage = null,
            onAccepted = { result = null },
            onDeclined = { result = null },
            onClose = { result = null },
        )
    }
}

/** A sentence in place of a control. Never a greyed button on its own — see [DwAiVerbsPanel]. */
@Composable
private fun DwMediaVerbNote(sentence: String) {
    Text(
        sentence,
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
