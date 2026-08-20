package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_VERB_MAX_LANGUAGE_CHARS
import com.designprototype.workshop.data.DW_VERB_MAX_TEXT_CHARS
import com.designprototype.workshop.data.DW_VERBS_NOTHING_SELECTED
import com.designprototype.workshop.data.DW_VERBS_WORKSHOP_NOT_ON_SERVER
import com.designprototype.workshop.data.DwAiVerbResultDto
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.DwVerbSource
import com.designprototype.workshop.data.dwTranslationTargetRefusal
import com.designprototype.workshop.data.dwVerbPassageRefusal
import com.designprototype.workshop.data.dwVerbPassageTooLong
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch

/**
 * PROOFREAD, WRITE OUT, TRANSLATE — under the prose field, scoped to the paragraph the caret is in.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHERE THESE BELONG ON A HANDSET IS NOT WHERE THEY BELONG IN A BROWSER, AND THIS IS THE ARGUMENT.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The browser puts the three text verbs in a dropdown inside the rich-text toolbar, acting on the
 * designer's SELECTION. Three facts about this editor make that the wrong shape here, and every one
 * of them is readable in the code rather than a matter of taste.
 *
 *  1. **A SELECTION IN THIS EDITOR CAN NEVER SPAN TWO PARAGRAPHS.** Every block is its own
 *     `BasicTextField`, and `RichTextBlockRow`'s `onSelectionChanged` builds
 *     `DocRange(DocPoint(index, range.start), DocPoint(index, range.end))` — **both ends carry the
 *     same block index**, because Android's selection handles cannot be dragged out of one
 *     composable into another. Porting the browser's `selectedPassage`, which walks blocks and joins
 *     them with newlines, would be porting a function whose multi-block branch is unreachable here.
 *     Worse than useless: a designer who drags down the screen believing they have chosen five
 *     paragraphs would silently send one, and the layer would record that one as the whole evidence
 *     — which is the exact class of falsehood rule 2 exists to prevent.
 *  2. **THE TOOLBAR EXISTS ONLY WHILE THE CARET IS IN THE FIELD.** `RichTextEditor` draws it under
 *     `if (focusedBlock != null && enabled)`, and `onFocused(false)` clears `focusedBlock`. A mark
 *     toggle survives that only because `RichTextToolbar` refuses focus for its entire subtree
 *     (`focusProperties { canFocus = false }`) and because a press is instantaneous. A verb is not
 *     one tap: choose it, name a language, wait out a round trip on one bar of signal, read a review
 *     sheet and decide. A control hosted in a bar that unmounts on focus loss would vanish in the
 *     middle of its own flow.
 *  3. **THE BAR IS ALREADY A `horizontalScroll` ROW OF EIGHTEEN CONTROLS PLUS A MICROPHONE.** A
 *     nineteenth item, off the right-hand edge, is a capability nobody finds twice.
 *
 * ── SO: A CARD UNDER THE FIELD, AND THE PASSAGE IS THE PARAGRAPH THE CARET IS IN ────────────────
 *
 * It sits outside the focus-conditional toolbar, so it survives every step of its own flow. It is
 * drawn whether or not the field has focus, which is right: running a verb is a considered act about
 * a passage, not a formatting gesture mid-word. And the passage is a whole paragraph — or the
 * shorter stretch inside it that the designer actually selected, used verbatim when there is one, so
 * nothing is taken away from somebody who did make a selection.
 *
 * **THE PASSAGE IS SHOWN BEFORE THE PRESS.** A phone has no visible highlight once the keyboard
 * closes, so the card prints the opening of what it is about to send. Without it, "this paragraph"
 * means whichever block the caret was last in, which after a scroll is not a fact the designer holds.
 *
 * ── WHAT WAS REJECTED, AND WHY ──────────────────────────────────────────────────────────────────
 *
 *  · **The toolbar dropdown** — the browser's placement. Reasons 1-3 above.
 *  · **A long-press action beside Cut and Copy** (Android's own `ActionMode`). The most native-feeling
 *    option, and refused: the ActionMode belongs to whichever `BasicTextField` owns the selection, so
 *    it would have to be installed per block of per field of 22 stages; it is torn down the instant
 *    any dialog opens, which is where the review sheet lives; and it would put these verbs in the
 *    same menu as Copy, which is the one affordance this feature may not appear to be a sibling of.
 *  · **FIELD-scoped rather than paragraph-scoped.** A narrative field's document bound is an order of
 *    magnitude above `DW_VERB_MAX_TEXT_CHARS`, so a whole-field control would be refused routinely on
 *    exactly the stage-13 narratives it is most wanted on, and a designer would learn — correctly —
 *    that the button is broken. A paragraph cannot do that except when it genuinely is 20,000
 *    characters, and `dwVerbPassageRefusal` then says so with the number in it.
 *  · **A workshop-wide "AI" screen listing every narrative field.** It moves the verb away from the
 *    evidence, which is the thing an acceptance is a judgement about. The browser rejected the same
 *    shape for the same reason — *"a 'proofread this' that lives three taps away on a provenance
 *    screen is a feature nobody uses"* — and the argument is stronger here, because a phone has no
 *    second window to hold the original in.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * **NOTHING IN THIS FILE PUTS THE MODEL'S WORDS BACK INTO THE FIELD**, on any verb. The full argument
 * is in [DwAiVerbReviewSheet]'s header; in short, the server cannot express that write (`DwStageEntry`
 * is absent from `WRITABLE_TABLES`), a RICH_TEXT field is compared across surfaces, and a clipboard
 * button is a paste button with one extra keystroke. `DwAiVerbSurfaceGuardTest` reads this file's
 * source and fails if one appears.
 */
@Composable
internal fun DwAiVerbsPanel(
    /** The form is read-only, or a save is in flight. */
    enabled: Boolean,
    /**
     * How long the passage is, so the too-long rung can be drawn before the press.
     *
     * ONLY THE LENGTH TRAVELS THROUGH THE RENDER PATH; the words are fetched once, at the press. That
     * is [readPassage]'s whole reason for being a callback, and it is the browser's reason too:
     * rebuilding a long passage on every arrow key is a stutter in the one composable that already
     * recomposes on every keystroke of a forty-page narrative.
     */
    passageChars: Int,
    /** The opening of the passage, so the designer can see what is about to be sent. */
    passagePreview: String,
    /** The passage itself, read LAZILY at the moment of the press. */
    readPassage: () -> String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * How many runs this panel has finished — the key that makes the allowance mirror re-read.
     *
     * `WorkshopRepository.runVerb` writes the mirror from the 201's own numbers and from a cap
     * refusal, and SharedPreferences is not observable, so a surface that did not change a key would
     * go on showing the count from before its own run. Bumped on EVERY settled run, success or
     * refusal, because `_count_refused_run` spends the allowance for anything that reached a provider
     * — including what then failed, "because the credit is spent by the call".
     */
    var runs by remember { mutableStateOf(0) }
    val surface = dwVerbSurface(runs)

    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DwAiVerbResultDto?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    /** The second step of translate: null while it is not being asked for, "" the moment it is. */
    var target by remember { mutableStateOf<String?>(null) }

    /*
      THE GATE IS EVALUATED WHEN THE CARD IS DRAWN, AND EVERY RUNG OF IT AGAIN AT THE PRESS.

      `surface.gate(context)` reaches `ConnectivityManager` through `getSystemService`, so it is called
      from the branch that draws the card rather than from the top of this composable — this panel
      lives under a field that recomposes on every keystroke, and a read in the composition body would
      be one system-service call per character typed.
    */
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "Working on the passage on the server…",
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    open = !open
                    if (!open) target = null
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (open) "Close" else "Ask AI about this paragraph")
            }
        }

        if (open && !running) {
            val gate = surface.gate(context)

            /** Run one verb. Re-checks every rung, because every one of them can change under a card. */
            fun start(verb: String, targetLanguage: String? = null) {
                problem = null
                /*
                  RE-READ AT THE PRESS AND NOT REUSED FROM THE DRAW.

                  This panel outlives the sync that gives a workshop its server id — the stage screen
                  keeps its mount across one — so the pair is read from the surface again here. A
                  blank is not an id, which is `publishWorkshopConsent`'s own rule: `""` would put an
                  empty path segment into `/design-workshops/{id}/ai-layers/…`, and okhttp preserves
                  it rather than collapsing it.
                */
                val repository = surface.repository
                val workshopId = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
                if (repository == null || workshopId == null) {
                    problem = DW_VERBS_WORKSHOP_NOT_ON_SERVER
                    return
                }
                val passage = readPassage()
                val refusal = dwVerbPassageRefusal(passage)
                if (refusal != null) {
                    /*
                      ONE RULE, ONE DECISION POINT, AND ONE SUBSTITUTED SENTENCE.

                      `dwVerbPassageRefusal` decides both rungs — is there a passage, and is it short
                      enough — and it is not re-decided here, because blankness re-implemented on one
                      client is how the two come to disagree about what is empty. What IS substituted
                      is the wording of the empty case: the shared sentence tells a designer to select
                      the words first, which is right in a browser and is an instruction this editor
                      cannot honour. See [DW_NO_PARAGRAPH_TO_WORK_ON]. The length refusal is passed
                      through untouched, numbers and argument included.
                    */
                    problem = if (refusal == DW_VERBS_NOTHING_SELECTED) {
                        DW_NO_PARAGRAPH_TO_WORK_ON
                    } else {
                        refusal
                    }
                    return
                }
                running = true
                scope.launch {
                    runCatching {
                        when (verb) {
                            "PROOFREAD" -> repository.designWorkshopProofread(
                                context, workshopId, DwVerbSource.Passage(passage)
                            )
                            "EXPAND" -> repository.designWorkshopExpand(context, workshopId, passage)
                            else -> repository.designWorkshopTranslate(
                                context,
                                workshopId,
                                DwVerbSource.Passage(passage),
                                targetLanguage.orEmpty(),
                            )
                        }
                    }.onSuccess { answer ->
                        sent = passage
                        result = answer
                        target = null
                        open = false
                        /*
                          THE ALLOWANCE IS NOT DECREMENTED HERE AND IS NOT DECREMENTED ANYWHERE.

                          A counter can move without a layer appearing — `_count_refused_run` spends
                          the allowance for any run that reached a provider and then failed, "because
                          the credit is spent by the call" — so a client that subtracted one per
                          success would drift low and would disagree with the server the moment
                          anything failed. `WorkshopRepository.runVerb` writes the mirror from the
                          201's own numbers on the way past, and from a cap refusal on the way out,
                          which is the one place that happens.
                        */
                    }.onFailure { error ->
                        // THE SERVER'S OWN SENTENCE, VERBATIM, WHATEVER THE STATUS — a consent 409, a
                        // cap 429, a placement 422 and the 503 that names a missing key all already
                        // say what the next move is. Only a request that reached nobody is answered
                        // in this client's words, because no server composed one.
                        problem = dwAiVerbProblem(error)
                    }
                    running = false
                    // AFTER both arms, so a refusal re-reads the mirror too — a designer can watch
                    // their remaining count fall by one and still be refused, because the run reached
                    // a provider before it failed. Leaving the old number up would tell them a run
                    // they paid for did not happen.
                    runs += 1
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (gate) {
                        /*
                          INERT AND SILENT, AND UNREACHABLE ON THIS CLIENT TODAY — see
                          [DwAiVerbSurface] for why `draftRead` is always true here (the stage screen
                          composes no field until the draft has been read and the consent published).
                          The arm is written out rather than folded into an `else` so that the day
                          that early return changes, this draws nothing instead of flashing "nobody
                          has been asked" over a workshop that has been asked.
                        */
                        DwVerbGate.StillReading -> Unit

                        is DwVerbGate.Refused -> DwVerbRefusal(gate.sentence)

                        DwVerbGate.Ready -> {
                            /*
                              THE PASSAGE RUNGS ARE THIS SURFACE'S OWN — the media surface works on a
                              file and has no passage at all.

                              DECIDED FROM THE LENGTH HERE AND FROM THE PASSAGE ITSELF AT THE PRESS,
                              which is not two rules: `dwVerbPassageRefusal` measures the same bound on
                              the real string, and the press is where a paragraph of nothing but spaces
                              is caught. Here only the length is known — that is the whole point of
                              `passageChars`, which is what keeps a forty-page narrative from being
                              rebuilt on every keystroke — so the length is what is compared.
                            */
                            val passageRefusal = when {
                                passageChars == 0 -> DW_NO_PARAGRAPH_TO_WORK_ON
                                passageChars > DW_VERB_MAX_TEXT_CHARS ->
                                    dwVerbPassageTooLong(passageChars)
                                else -> null
                            }
                            if (passageRefusal != null) {
                                DwVerbRefusal(passageRefusal)
                            } else {
                                Text(
                                    "This paragraph is what will be sent, and it is what the layer " +
                                        "records as its source:",
                                    color = MaterialTheme.field.muted,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                )
                                Text(
                                    "“$passagePreview”",
                                    color = MaterialTheme.field.body,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (target == null) {
                                    DwVerbChoice("Proofread this passage") { start("PROOFREAD") }
                                    // "Write this note out" and not "expand": the verb's whole risk is
                                    // that it INVENTS, and "expand" reads as a note that got longer.
                                    // The heading on the result says the strongest true thing about
                                    // it; this says the plainest true thing about the request.
                                    DwVerbChoice("Write this note out") { start("EXPAND") }
                                    DwVerbChoice("Translate this passage") {
                                        // A SECOND STEP, because `targetLanguage` is required and is a
                                        // choice only the designer can make. `sourceLanguage` is
                                        // deliberately never asked for: it is an OBSERVATION the run
                                        // may already have made, and the server records what it
                                        // detected rather than defaulting it to English.
                                        target = ""
                                    }
                                } else {
                                    DwVerbTranslateStep(
                                        value = target.orEmpty(),
                                        onValue = { target = it },
                                        onCancel = { target = null },
                                        onTranslate = { start("TRANSLATE", it) },
                                    )
                                }
                                dwAiVerbCountdownLine(surface.cap.remaining, surface.today)?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.field.warning,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                                if (surface.cap.limit == null && surface.cap.remaining == null) {
                                    /*
                                      THE MISSING PRE-FLIGHT, STATED RATHER THAN GUESSED AT.

                                      `ai_verb_cap.allowance_payload` rides on the 201 and on the 429
                                      and nowhere else — there is no route that answers "what is my
                                      allowance", which was checked against `backend/app/api/routes/`
                                      rather than assumed. So until a run has gone past on this phone
                                      today there is no number, and this cannot say whether the
                                      ceiling is near, far or absent. Silence would leave a designer
                                      discovering it as a refusal after typing a language in.
                                    */
                                    Text(
                                        "How many runs are left today is not known until one goes " +
                                            "through — this server has no way to be asked without " +
                                            "running something. If the allowance is already used " +
                                            "up, the refusal will say so and nothing will have been " +
                                            "spent finding out.",
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
        }

        problem?.let {
            Text(
                it,
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                // Announced: the sentence can be several rows below the button that produced it, and
                // on a phone it is routinely below the fold with the keyboard open.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    val answer = result
    // A blank is not an id — `publishWorkshopConsent`'s rule, applied again here because accept,
    // decline and the subtitle file are three more server routes and all three would go out under an
    // empty path segment. Unreachable in practice (a result exists only where a run succeeded, which
    // needed an id), and the sheet takes a non-null String rather than an assertion.
    val serverId = surface.serverWorkshopId?.takeIf { it.isNotBlank() }
    val repository = surface.repository
    if (answer != null && repository != null && serverId != null) {
        DwAiVerbReviewSheet(
            result = answer,
            repository = repository,
            serverWorkshopId = serverId,
            sentPassage = sent,
            // THE EDITOR'S OWN DOCUMENT IS UNTOUCHED BY EVERY OUTCOME, which is why all three of
            // these do the same thing. There is no list on this surface to refresh, and there is
            // nothing to write back — see this file's header.
            onAccepted = { result = null },
            onDeclined = { result = null },
            onClose = { result = null },
        )
    }
}

/** One verb, full width and stacked, because these labels are sentences and would truncate in a row. */
@Composable
private fun DwVerbChoice(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/**
 * THE REASON, IN PLACE OF THE CONTROL rather than beside it.
 *
 * Never a disabled button on its own: `AiLayersPanel`'s rule — *"a control offered into a certain
 * refusal teaches designers that refusals are noise, after which the one that matters is clicked
 * through too"* — and never a tooltip, which does not exist on a touch screen and is read only by
 * somebody who already suspected there was something to read.
 */
@Composable
private fun DwVerbRefusal(sentence: String) {
    Text(
        sentence,
        color = MaterialTheme.field.muted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Naming the language to translate INTO. The one thing on this surface the designer must supply. */
@Composable
private fun DwVerbTranslateStep(
    value: String,
    onValue: (String) -> Unit,
    onCancel: () -> Unit,
    onTranslate: (String) -> Unit,
) {
    val refusal = dwTranslationTargetRefusal(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= DW_VERB_MAX_LANGUAGE_CHARS) onValue(it) },
            label = { Text("Translate into") },
            placeholder = { Text("Odia, Hindi, English…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        // THE COPY EXPLAINS THE BOUND RATHER THAN OFFERING A LIST. There is deliberately no picker:
        // this fleet works in nineteen languages and several of them have no code at all, so a list
        // would refuse the exact languages this system exists to record.
        Text(
            "A name or a code — “Odia”, “or”, “English”. There is no list to choose from on " +
                "purpose: several of the languages in these recordings have no code at all. The " +
                "original stays exactly where it is; a translation stands beside it.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        // Drawn only once the designer has typed something. The empty-field refusal is an instruction
        // rather than a fault, and printing it before they have had a chance to type is telling
        // somebody off for not having started.
        if (refusal != null && value.isNotBlank()) {
            Text(refusal, color = MaterialTheme.field.warning, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Button(
            onClick = { onTranslate(value.trim()) },
            enabled = refusal == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Translate this passage") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
