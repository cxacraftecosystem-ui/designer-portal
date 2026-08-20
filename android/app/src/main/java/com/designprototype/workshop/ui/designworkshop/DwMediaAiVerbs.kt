package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_SUBTITLES_SECOND_UPLOAD_NOTE
import com.designprototype.workshop.data.DW_VERBS_MEDIA_NOT_UPLOADED
import com.designprototype.workshop.data.DW_VERBS_WORKSHOP_NOT_ON_SERVER
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.dwVerbMediaRefusal
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
 * rejected for a concrete reason rather than a stylistic one: **there is no endpoint that lists a
 * workshop's images and videos with their stage and field labels.** `GET /{id}/transcripts` is
 * AUDIO-only (`load_transcript_items` walks `audio_references`), so a batch screen would need a new
 * backend route. The tile needs none.
 *
 * ── EACH FILE IS OFFERED ONLY THE VERB ITS TYPE ADMITS ──────────────────────────────────────────
 *
 * [dwMediaVerbsFor] mirrors `_VERB_MEDIA_TYPES` exactly, so this client never produces the 409 that
 * ends *"Choose another file — nothing was sent anywhere and nothing was spent."* The server checks
 * it before any bytes move precisely because the failure otherwise is expensive and unreadable: a
 * caption run over an audio file uploads a recording to a vision model, which answers with a parse
 * error after the credit is spent, and the designer reads "FAILED (HTTP 400)" about a file they
 * picked correctly.
 *
 * ── **NOTHING HERE WRITES A CAPTION INTO THE FIELD'S OWN CAPTION BOX** ──────────────────────────
 *
 * The registry carries `caption_for` fields and this handset draws them, in [FieldRenderer]'s own
 * words, "INSIDE this field's block, directly under the media it describes" — so the box is right
 * there and the temptation is obvious. It is refused for the reason written out in full in
 * [DwAiVerbReviewSheet]'s header: a caption written into a `caption_for` field is an AI value in a
 * field compared across surfaces, which plan §3 forbids and which the server cannot even express
 * (`DwStageEntry` is absent from `WRITABLE_TABLES`). The server's own consent refusal for CAPTION
 * already names the honest alternative — *"Write the description yourself in the caption box under
 * the photograph, where the stage has one"* — and that is a designer's sentence under a designer's
 * name, which is a true statement no paste button could produce.
 *
 * ── TWO SEPARATE "NOT ON THE SERVER YET" STATES, AND THEY ARE NOT INTERCHANGEABLE ───────────────
 *
 * `DW_VERBS_MEDIA_NOT_UPLOADED` is about ONE FILE: a descriptor whose `remoteMediaId` is still null
 * names nothing the server has ever seen, so there is nothing to work on even on a perfect
 * connection, and the other tiles of the same field are unaffected. `DW_VERBS_WORKSHOP_NOT_ON_SERVER`
 * is about the WHOLE WORKSHOP. The browser shipped with the second one missing, offering both verbs
 * on every tile of every unsynced workshop into a bare "Record not found"; here the workshop rung
 * comes from `dwVerbGate`, which every surface shares, so the two cannot come apart.
 */
@Composable
internal fun DwMediaAiVerbsRow(
    item: DwMediaItem,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val verbs = remember(item.mediaType) { dwMediaVerbsFor(item.mediaType) }
    if (verbs.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * How many runs this row has finished — the key that makes the allowance mirror re-read.
     *
     * See [dwVerbSurface]: the mirror is SharedPreferences, which is not observable, so without a
     * changing key a countdown would keep the number from before this row's own run. Bumped on a
     * refusal as well as on a success, because a run that reached a provider and then failed has
     * still spent the credit.
     */
    var runs by remember(item.id) { mutableStateOf(0) }
    val surface = dwVerbSurface(runs)

    /**
     * Whether the designer has asked for this tile's verbs.
     *
     * ── WHY THIS ROW IS A TOGGLE AND NOT TWO PERMANENT BUTTONS ──────────────────────────────────
     *
     * Two reasons, and both bite on a phone rather than in a browser. An IMAGE_LIST field routinely
     * holds a dozen photographs, so two full-width buttons and a three-line subtitle caution PER
     * TILE would push the stage's next actual question several screens down — which is the argument
     * `RichTextToolbar` already makes about drawing one formatting bar per narrative field. And every
     * rung of the ladder costs something to evaluate: `surface.gate` reaches `ConnectivityManager`
     * and the duplicate-subtitle check is a network read, so a dozen tiles evaluating both on every
     * recomposition of a stage that recomposes on every keystroke is a dozen binder calls and a
     * request per character typed.
     *
     * Collapsed, this row is one line. Opened, it reads the connection and asks the server what has
     * already been subtitled — which is exactly when those two answers are wanted, and when they are
     * freshest.
     */
    var open by remember(item.id) { mutableStateOf(false) }
    var running by remember(item.id) { mutableStateOf<String?>(null) }
    var problem by remember(item.id) { mutableStateOf<String?>(null) }
    var result by remember(item.id) { mutableStateOf<DwAiVerbResultDto?>(null) }

    /**
     * Which of this workshop's server media ids already carry a live SUBTITLES layer.
     *
     * **NULL IS "NOT KNOWN" AND IS NEVER FLATTENED TO AN EMPTY SET.** An empty set would say "this
     * recording has not been subtitled", which is a confident wrong answer that invites a designer to
     * spend a paid SECOND upload of audio they have already paid to upload once — the cost
     * `DW_SUBTITLES_SECOND_UPLOAD_NOTE` exists to warn about. A read that fails stays null and the
     * control says so rather than implying either answer.
     */
    var subtitled by remember(surface.serverWorkshopId) { mutableStateOf<Set<String>?>(null) }

    /*
      THE GATE AND THE DUPLICATE CHECK ARE BOTH READ WHEN THE ROW IS OPENED.

      Keyed on [open] rather than on the tile, so a designer who walked out of range with the row
      shut and back into it before opening one gets the answer that is true now. `StillReading` is the
      state before either has answered, and it draws nothing at all.

      A DESIGNER WHO LOSES SIGNAL BETWEEN OPENING THE ROW AND PRESSING IS ANSWERED BY THE FAILURE AND
      NOT BY A SECOND GATE READ. This comment used to say `start` re-checked the gate; it does not —
      it re-reads the workshop pair and `dwVerbMediaRefusal`, which are the two facts that change in
      the direction that makes a verb POSSIBLE. A press made with no signal reaches `dwAiVerbProblem`,
      whose `IOException` arm returns the same `DW_VERBS_NEED_A_CONNECTION` the gate would have shown,
      so the sentence is identical and nothing is spent: no request reaches the server.
    */
    var gate by remember(item.id) { mutableStateOf<DwVerbGate>(DwVerbGate.StillReading) }
    LaunchedEffect(open, item.id, surface.serverWorkshopId, surface.consent) {
        if (!open) {
            // BACK TO SILENT ON CLOSE, so reopening never shows the answer from last time for the
            // frame before this effect lands. A designer who shut the row in a courtyard and reopens
            // it at the guest house must not read "this phone has no connection" over a phone that
            // now has one.
            gate = DwVerbGate.StillReading
            return@LaunchedEffect
        }
        gate = surface.gate(context)
        val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
        val repository = surface.repository
        if (MEDIA_VERB_SUBTITLES in verbs && repository != null && workshop != null) {
            /*
              ASKED ONLY FOR THE ONE KIND, AND WITHOUT THE TEXT.

              `designWorkshopAiLayers` defaults `includeText` off for the reason its own KDoc gives —
              a workshop can hold twenty-five interviews and a list with the prose in would be
              megabytes on one bar of signal. Narrowing by `kind` means the answer is the handful of
              SUBTITLES rows rather than every layer the workshop has ever produced.

              A FAILED READ LEAVES THIS NULL. `runCatching` here is not swallowing an error a designer
              needs: this read decides only whether to warn about a duplicate upload, and the sentence
              below says plainly that the phone could not check.
            */
            subtitled = runCatching {
                repository.designWorkshopAiLayers(workshop, kind = "SUBTITLES")
                    .items
                    .filter { it.deletedAt == null }
                    .mapNotNull { layer -> layer.source?.id?.takeIf { it.isNotBlank() } }
                    .toSet()
            }.getOrNull()
        }
    }

    val serverMediaId = item.remoteMediaId?.takeIf { it.isNotBlank() }

    fun start(verb: String) {
        problem = null
        // A blank is not an id — `publishWorkshopConsent`'s rule, which argues the case at length:
        // `""` would put an empty path segment into `/design-workshops/{id}/ai-layers/…`, and okhttp
        // preserves it rather than collapsing it.
        val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
        val repository = surface.repository
        if (repository == null || workshop == null) {
            problem = DW_VERBS_WORKSHOP_NOT_ON_SERVER
            return
        }
        // RE-CHECKED AT THE PRESS. A file finishes uploading while its tile is on screen — that is
        // precisely the moment `remoteMediaId` is written — so the id read when the row was drawn can
        // be a fact that has changed, in the direction that makes the verb possible rather than
        // impossible. `dwVerbMediaRefusal` is the shared rung and not a second opinion about it.
        dwVerbMediaRefusal(item.remoteMediaId)?.let {
            problem = it
            return
        }
        // The rung above returns null only for a non-blank id, so this branch is unreachable — but
        // the compiler cannot see that across a function boundary, and `!!` inside a click handler is
        // how a designer's tap becomes a crash. Refused in the same words instead.
        val mediaId = item.remoteMediaId?.takeIf { it.isNotBlank() } ?: run {
            problem = DW_VERBS_MEDIA_NOT_UPLOADED
            return
        }
        running = verb
        scope.launch {
            runCatching {
                if (verb == MEDIA_VERB_SUBTITLES) {
                    repository.designWorkshopSubtitleMedia(context, workshop, mediaId)
                } else {
                    // `language` is deliberately not asked for. A caption in the model's own language
                    // is a perfectly good answer, `multi` is dropped by the server rather than
                    // refused, and asking a designer to name a language for a sentence nobody has
                    // written yet is asking them to assert a fact about output that does not exist.
                    repository.designWorkshopCaptionMedia(context, workshop, mediaId)
                }
            }.onSuccess { answer ->
                result = answer
                // A SUBTITLES run that landed means this recording now has one, whatever the read
                // above did or did not answer. Recorded so a second tap does not buy a second upload
                // of the same audio while this screen is still open.
                if (verb == MEDIA_VERB_SUBTITLES) {
                    subtitled = (subtitled ?: emptySet()) + mediaId
                }
            }.onFailure { error ->
                problem = dwAiVerbProblem(error)
            }
            running = null
            runs += 1
        }
    }

    // Read into a local so the `when` below can smart-cast it. A `by mutableStateOf` property is a
    // delegate, which the compiler will not narrow, and the alternative is an `as` cast inside a
    // branch that has just tested the type.
    val currentGate = gate

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    // TWO SENTENCES BECAUSE TWO DIFFERENT WAITS. A caption is a few kilobytes of
                    // description coming back; subtitling is a whole recording going UP, over this
                    // designer's own data, and a spinner that said the same thing for both would hide
                    // the one a designer on a metered connection needs to know about.
                    if (running == MEDIA_VERB_SUBTITLES) {
                        "Sending this recording up for subtitling…"
                    } else {
                        "Asking a model to describe this…"
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                )
            }
        } else {
            TextButton(onClick = { open = !open }, enabled = enabled) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (open) "Close" else "Ask AI about this file", fontSize = 12.sp)
            }
        }

        when {
            // The row is shut, or a run is in flight and the spinner above is the whole of it.
            !open || running != null -> Unit

            // INERT AND SILENT while neither answer has landed. The floor for the duplicate check is
            // "not known" and the floor for the gate is a refusal, so drawing either before the read
            // would tell a designer something nobody has established — which is the whole reason
            // `DwVerbGate.StillReading` exists.
            currentGate is DwVerbGate.StillReading -> Unit

            currentGate is DwVerbGate.Refused -> DwMediaVerbNote(currentGate.sentence)

            serverMediaId == null -> DwMediaVerbNote(DW_VERBS_MEDIA_NOT_UPLOADED)

            else -> {
                if (MEDIA_VERB_CAPTION in verbs) {
                    OutlinedButton(
                        onClick = { start(MEDIA_VERB_CAPTION) },
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
                if (MEDIA_VERB_SUBTITLES in verbs) {
                    if (subtitled?.contains(serverMediaId) == true) {
                        DwMediaVerbNote(
                            "This recording already has AI subtitles on this workshop, so making " +
                                "them again would send the audio up a second time and spend another " +
                                "run for a cue list that already exists."
                        )
                    } else {
                        OutlinedButton(
                            onClick = { start(MEDIA_VERB_SUBTITLES) },
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
                          upload of bytes the archive already holds, and on a handset that upload comes
                          out of the designer's own data bundle. The route's own docstring calls it "a
                          defect rather than a design": every timing this system has ever received was
                          discarded one line after being parsed.
                        */
                        Text(
                            DW_SUBTITLES_SECOND_UPLOAD_NOTE,
                            color = MaterialTheme.field.muted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                        // AND WHERE THE READ THAT WOULD HAVE ANSWERED "already done" NEVER LANDED.
                        // Silence would let a designer spend that upload twice with nothing on screen
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
                dwAiVerbCountdownLine(surface.cap.remaining, surface.today)?.let {
                    Text(it, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
                }
                /*
                  AND WHAT THE COUNTDOWN CANNOT SAY, WHICH THIS SURFACE USED TO SAY NOTHING ABOUT.

                  `dwAiVerbCountdownLine` answers null whenever there is no `remaining` to count, which
                  covers BOTH "this phone has not been told an allowance" and "this deployment has no
                  ceiling" — so this row was silent in both. [DwAiVerbsPanel] printed a sentence for
                  them and this did not, and nothing argued for the difference. Its argument applies
                  here with more force than there: the press below can be a whole recording going up
                  over the designer's own mobile data, so discovering the ceiling as a refusal
                  afterwards costs the upload as well as the wait.

                  ONE COPY OF BOTH SENTENCES, in [dwAiVerbAllowanceNote], for the reason the countdown
                  is one copy — the browser's own review found this feature's cap wording computed
                  inline in three places. Muted rather than `field.warning`: neither sentence is a
                  refusal, and the countdown above is the line that is.
                */
                dwAiVerbAllowanceNote(surface.cap)?.let {
                    Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
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

    val answer = result
    val workshop = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
    val repository = surface.repository
    if (answer != null && repository != null && workshop != null) {
        DwAiVerbReviewSheet(
            result = answer,
            repository = repository,
            serverWorkshopId = workshop,
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
