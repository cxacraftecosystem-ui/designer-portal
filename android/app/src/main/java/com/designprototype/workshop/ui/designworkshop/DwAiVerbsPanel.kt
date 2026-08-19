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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The web puts the three text verbs in a dropdown inside the rich-text toolbar, acting on the
 * designer's SELECTION. Three facts about this editor make that the wrong shape here, and each is
 * readable in the code rather than a matter of taste.
 *
 *  1. **A SELECTION IN THIS EDITOR CAN NEVER SPAN TWO PARAGRAPHS.** Every block is its own
 *     `BasicTextField`, and `RichTextBlockRow`'s `onSelectionChanged` builds
 *     `DocRange(DocPoint(index, range.start), DocPoint(index, range.end))` — both ends carry the
 *     SAME block index, because Android's selection handles cannot be dragged from one composable
 *     into another. Porting the web's `selectedPassage`, which walks blocks and joins them with
 *     newlines, would be porting a function whose multi-block branch is unreachable on this client.
 *     Worse than useless: a designer who drags down the screen believing they have chosen five
 *     paragraphs would silently send one, and the layer would record that one as the whole evidence.
 *  2. **THE TOOLBAR EXISTS ONLY WHILE THE CARET IS IN THE FIELD.** `RichTextEditor` draws it under
 *     `if (focusedBlock != null && enabled)`, and `onFocused(false)` clears `focusedBlock`. A mark
 *     toggle survives that because `RichTextToolbar` refuses focus for its whole subtree
 *     (`focusProperties { canFocus = false }`) and a press is instantaneous. A verb is not one tap:
 *     choose it, name a language, wait out a round trip on one bar of signal, read a review sheet and
 *     decide. Every one of those moves focus, and a control hosted in a bar that unmounts on focus
 *     loss would vanish in the middle of its own flow.
 *  3. **THE BAR IS ALREADY A `horizontalScroll` ROW OF EIGHTEEN CONTROLS PLUS A MICROPHONE.** A
 *     nineteenth item, off-screen to the right, is a capability nobody finds twice.
 *
 * ── SO: A CARD UNDER THE FIELD, AND THE PASSAGE IS THE PARAGRAPH THE CARET IS IN ────────────────
 *
 * It sits outside the focus-conditional toolbar, so it survives every step of its own flow. It is
 * drawn whether or not the field has focus, which is right: a verb is a considered act about a
 * passage, not a formatting gesture mid-word. And the passage is a whole paragraph — or the shorter
 * stretch inside it that the designer actually selected, used verbatim when there is one.
 *
 * **THE PASSAGE IS SHOWN BEFORE THE PRESS.** A phone has no highlight the designer can see once the
 * keyboard closes, so the card prints the opening of what it is about to send. Without it, "this
 * paragraph" means whichever block the caret was last in, which after scrolling is not a fact the
 * designer holds.
 *
 * ── WHAT WAS REJECTED, AND WHY ──────────────────────────────────────────────────────────────────
 *
 *  · **The toolbar dropdown** — the web's placement. Reasons 1-3 above.
 *  · **A long-press action on the text selection** (Android's own `ActionMode`, beside Cut/Copy).
 *    It is the most native-feeling option and it is refused: the ActionMode belongs to whichever
 *    `BasicTextField` owns the selection, so it would have to be installed per block of per field of
 *    22 stages; it is torn down the instant any dialog opens, which is where the review sheet lives;
 *    and it sits in the same menu as Copy, which is the one affordance this feature may not appear
 *    to be a sibling of.
 *  · **FIELD-scoped rather than paragraph-scoped.** A narrative field's document bound is far above
 *    [DW_MAX_VERB_TEXT_CHARS], so a whole-field control would be refused routinely on exactly the
 *    stage-13 narratives it is most wanted on, and a designer would learn — correctly — that the
 *    button is broken. A paragraph cannot do that except when it genuinely is 20,000 characters,
 *    which [dwPassageTooLong] then says with both numbers in it.
 *  · **A workshop-wide "AI" screen listing every narrative field.** It moves the verb away from the
 *    evidence, which is the thing acceptance is a judgement about. The web rejected the same shape
 *    for the same reason: *"a 'proofread this' that lives three taps away on a provenance screen is a
 *    feature nobody uses."* Stronger here — a phone has no second window to hold the original in.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * **NOTHING IN THIS FILE PUTS THE MODEL'S WORDS BACK INTO THE FIELD**, on any verb. The full
 * argument is in [DwAiVerbReviewSheet]'s header; in short, the server cannot express that write
 * (`DwStageEntry` is absent from `WRITABLE_TABLES`), a RICH_TEXT field is compared across surfaces,
 * and a clipboard button is a paste button with one extra keystroke. This file is read by
 * `DwAiVerbReviewGuardTest` and the test fails if one appears.
 */
@Composable
internal fun DwAiVerbsPanel(
    bridge: DwAiVerbBridge,
    /** The form is read-only, or a save is in flight. */
    enabled: Boolean,
    /**
     * How long the passage is, in characters, so the too-long rung can be drawn before the press.
     *
     * ONLY THE LENGTH TRAVELS THROUGH THE RENDER PATH and the words are fetched once, at the press —
     * `readPassage` is a callback for the reason the web's is: rebuilding a long passage on every
     * arrow key is a stutter in the one composable that already recomposes on every keystroke.
     */
    passageChars: Int,
    /** The opening of the passage, ellipsised by the caller, so the designer can see what is sent. */
    passagePreview: String,
    /** The passage itself, read LAZILY at the moment of the press. */
    readPassage: () -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var open by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<DwAiVerb?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DwAiVerbRun?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    /** The second step of translate: null while it is not being asked for, "" the moment it is. */
    var target by remember { mutableStateOf<String?>(null) }

    /*
      CONNECTIVITY IS READ WHEN THE CARD IS OPENED AND AGAIN AT THE PRESS, AND NEVER PER RECOMPOSITION.

      `bridge.isOnline()` reaches `ConnectivityManager` through `getSystemService`, and this panel
      lives under a field that recomposes on every keystroke of a long narrative — a read in the
      composition body would be one system-service call per character typed. Reading it when the card
      opens is when the answer is actually wanted, and re-reading it at the press is what makes the
      control honest for a designer who walked out of range in between.
    */
    var online by remember { mutableStateOf(true) }
    LaunchedEffect(open) { if (open) online = bridge.isOnline() }

    val gate = dwVerbWorkshopGate(bridge.serverWorkshopId, bridge.consent, online)
    val meter = DwAiVerbMeter.current
    val ceiling = dwVerbAllowanceRefusal(meter?.remaining, DwAiVerbMeter.refusal)

    /** Run one verb. Every rung is re-checked HERE, because every one of them can change under a card. */
    fun start(verb: DwAiVerb, targetLanguage: String? = null) {
        problem = null
        val serverId = bridge.serverWorkshopId
        // RE-READ AT THE PRESS. This panel outlives the sync that gives a workshop its server id —
        // the stage screen keeps its mount across one — so an id read at composition is a fact that
        // can change under a control still on screen.
        if (serverId.isNullOrBlank()) {
            problem = DW_WORKSHOP_NOT_ON_SERVER_YET
            return
        }
        if (!bridge.isOnline()) {
            online = false
            problem = DW_VERBS_NEED_A_CONNECTION
            return
        }
        val passage = readPassage()
        if (passage.isBlank()) {
            problem = DW_NOTHING_TO_WORK_ON
            return
        }
        if (passage.length > DW_MAX_VERB_TEXT_CHARS) {
            problem = dwPassageTooLong(passage.length)
            return
        }
        running = verb
        scope.launch {
            val outcome = when (verb) {
                DwAiVerb.PROOFREAD -> bridge.proofread(passage)
                DwAiVerb.EXPAND -> bridge.expand(passage)
                DwAiVerb.TRANSLATE -> bridge.translate(passage, targetLanguage.orEmpty())
                // Neither media verb is reachable from a prose panel; the `when` is exhaustive
                // because the enum is, and an unreachable arm that lied would be worse than one that
                // says so.
                DwAiVerb.CAPTION, DwAiVerb.SUBTITLES -> null
            }
            when (outcome) {
                is DwAiVerbOutcome.Produced -> {
                    sent = passage
                    result = outcome.run
                    target = null
                    open = false
                    /*
                      THE ALLOWANCE IS RE-READ FROM THE ANSWER AND NEVER DECREMENTED BY ONE.

                      A counter can move without a layer appearing: `_count_refused_run` spends the
                      allowance for any run that reached a provider and then failed, "because the
                      credit is spent by the call". A client that subtracted one per success would
                      drift low and would report a count that disagreed with the server's the moment
                      anything failed. The 201 carries `allowance_payload` for exactly this.
                    */
                    DwAiVerbMeter.learn(outcome.run.allowance, refusal = null)
                }

                is DwAiVerbOutcome.Refused -> {
                    // THE SERVER'S OWN SENTENCE, VERBATIM, WHATEVER THE STATUS. A consent 409, a cap
                    // 429, a placement 422 and a 503 saying no key is configured all already name the
                    // next move, and a second voice on any of those rules is how a client and a
                    // server come to disagree about what a refusal means.
                    problem = outcome.sentence
                    // AND THE ALLOWANCE IS LEARNED FROM THE REFUSAL TOO: a designer can watch their
                    // remaining count fall by one and still be refused, because the run reached a
                    // provider before it failed. Leaving the old number up would tell them a run they
                    // paid for did not happen.
                    outcome.allowance?.let { DwAiVerbMeter.learn(it, refusal = outcome.sentence) }
                }

                DwAiVerbOutcome.Offline -> {
                    online = false
                    problem = DW_VERBS_NEED_A_CONNECTION
                }

                null -> problem = DW_NOTHING_TO_WORK_ON
            }
            running = null
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running != null) {
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
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (open) "Close" else "Ask AI about this paragraph")
            }
        }

        if (open && running == null) {
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
                        // THIS SURFACE NEVER SEES `Waiting` AND THE ARM IS STILL WRITTEN OUT.
                        // `StageScreen` returns "Opening the stage…" and composes no field at all
                        // until the draft has been read and the consent published, so the floor
                        // answer this ladder fails closed to is never on screen — which is the
                        // structural version of the guard the web needed a three-valued string for.
                        // Drawing nothing is the correct behaviour if that ever stops being true.
                        DwVerbGate.Waiting -> Unit

                        is DwVerbGate.Refused -> DwVerbRefusal(gate.sentence)

                        DwVerbGate.Ready -> {
                            val tooLong = passageChars > DW_MAX_VERB_TEXT_CHARS
                            when {
                                passageChars == 0 -> DwVerbRefusal(DW_NOTHING_TO_WORK_ON)
                                tooLong -> DwVerbRefusal(dwPassageTooLong(passageChars))
                                ceiling != null -> DwVerbRefusal(ceiling)
                                else -> {
                                    Text(
                                        "This paragraph is what will be sent, and it is what the " +
                                            "layer records as its source:",
                                        color = MaterialTheme.field.muted,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                    )
                                    Text(
                                        "“$passagePreview”",
                                        color = MaterialTheme.field.body,
                                        fontSize = 12.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (target == null) {
                                        DwVerbChoice("Proofread this passage") {
                                            start(DwAiVerb.PROOFREAD)
                                        }
                                        // "Write this note out" and not "expand": the verb's whole
                                        // risk is that it INVENTS, and "expand" reads as a note that
                                        // got longer. The heading on the result says the strongest
                                        // true thing about it; this says the plainest true thing
                                        // about the request.
                                        DwVerbChoice("Write this note out") {
                                            start(DwAiVerb.EXPAND)
                                        }
                                        DwVerbChoice("Translate this passage") {
                                            // A SECOND STEP, because `targetLanguage` is required and
                                            // is a choice only the designer can make. `sourceLanguage`
                                            // is deliberately never asked for: it is an OBSERVATION
                                            // the run may already have made, and the server records
                                            // what it detected rather than defaulting it to English.
                                            target = ""
                                        }
                                    } else {
                                        DwVerbTranslateStep(
                                            value = target.orEmpty(),
                                            onValue = { target = it },
                                            onCancel = { target = null },
                                            onTranslate = { start(DwAiVerb.TRANSLATE, it) },
                                        )
                                    }
                                    dwAiVerbCountdown(meter?.remaining, meter?.day)?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.field.warning,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                    if (meter == null) {
                                        /*
                                          THE MISSING PRE-FLIGHT, STATED RATHER THAN GUESSED AT.

                                          `ai_verb_cap.allowance_payload` rides on the 201 and on the
                                          429, and there is NO route that answers "what is my
                                          allowance" without running a verb — checked against
                                          `backend/app/api/routes/` rather than assumed. So until
                                          this designer's first run of the session there is no number
                                          to show, and this phone cannot say whether the ceiling is
                                          near, far or absent. Silence about a ceiling would be a
                                          designer discovering it as a 429 after typing a language in.
                                        */
                                        Text(
                                            "Today's remaining runs are not known until the first " +
                                                "one — this server has no way to be asked without " +
                                                "running something. If the daily allowance is " +
                                                "already used up, the refusal will say so and " +
                                                "nothing will have been spent on finding out.",
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
        }

        problem?.let {
            Text(
                it,
                color = MaterialTheme.field.warning,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                // Announced: the sentence can be several rows below the button that produced it, and
                // on a phone it is routinely below the fold when the keyboard is open.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    result?.let { run ->
        DwAiVerbReviewSheet(
            run = run,
            bridge = bridge,
            sentPassage = sent,
            // THE EDITOR'S OWN DOCUMENT IS UNTOUCHED BY EVERY OUTCOME, which is why all three of
            // these do the same thing. There is no list on this surface to refresh and there is
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
 * through too"* — and never a tooltip either, which does not exist on a touch screen and is read only
 * by somebody who already suspected there was something to read.
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
            onValueChange = { if (it.length <= DW_MAX_VERB_LANGUAGE_CHARS) onValue(it) },
            label = { Text("Translate into") },
            placeholder = { Text("Odia, Hindi, English…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        // THE COPY EXPLAINS THE BOUND RATHER THAN A LIST. There is deliberately no picker: this fleet
        // works in nineteen languages and several of them have no code at all, so a list would refuse
        // the exact languages this system exists to record.
        Text(
            "A name or a code — “Odia”, “or”, “English”. There is no list to choose from on " +
                "purpose: several of the languages in these recordings have no code at all. The " +
                "original stays exactly where it is; a translation stands beside it.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        // Drawn only once the designer has typed something. The empty-field refusal is an
        // instruction, not a fault, and printing it before they have had a chance to type is telling
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

/**
 * TODAY'S ALLOWANCE AS THIS PROCESS LAST HEARD IT, SO ONE STAGE'S CONTROLS ALL SHOW ONE NUMBER.
 *
 * ── WHY IT IS HELD AT ALL ───────────────────────────────────────────────────────────────────────
 *
 * There is no route that answers "what is my allowance" — `ai_verb_cap.allowance_payload` rides on
 * the 201 and on the 429 and nowhere else, which was checked against `backend/app/api/routes/`
 * rather than assumed. So the only way this phone ever learns the number is by a run going by, and a
 * number thrown away between two fields of one stage would be a countdown that appears once and then
 * disappears — worse than none, because a designer would conclude it had reset.
 *
 * **IT ENFORCES NOTHING.** The count that decides anything is a row in the server's
 * `DwAiVerbDailyUsage`, exactly as `DwDictationAllowance` says of its own copy: *"a client-side
 * counter is a client-side counter … NOTHING HERE ENFORCES ANYTHING."* What it buys is the refusal
 * happening before a request goes out on a metered rural connection.
 *
 * ── WHY IT IS PROCESS-SCOPED AND NOT PERSISTED, WHICH IS THE OPPOSITE OF THE DICTATION CAP ──────
 *
 * `DwDictationAllowance` uses SharedPreferences because the thing it saves is a SIX-MEGABYTE UPLOAD
 * per field: a refusal learned at that price must survive a swipe-away. A verb costs a small JSON
 * body, so the same argument does not carry, and the cost of persisting is the one the web names —
 * a field handset is shared, sign-out clears the token and deliberately nothing else, so a stored
 * count would follow one designer's afternoon into the next designer's session.
 *
 * **SAID PLAINLY BECAUSE IT IS STILL TRUE WITHIN ONE PROCESS**: a phone handed over without being
 * killed shows the previous designer's remaining count until the next run replaces it. That is a
 * wrong NUMBER and never a wrong permission — nothing here gates anything the server does not gate
 * itself — and [forget] is what closes it. Sign-out is the place to call it.
 */
object DwAiVerbMeter {
    private var state by androidx.compose.runtime.mutableStateOf<DwAiVerbAllowance?>(null)
    private var lastRefusal by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The allowance this process last heard, or null if it has heard none. */
    val current: DwAiVerbAllowance? get() = state

    /** `cap_refusal`'s own sentence from the last refusal that carried one. Never composed here. */
    val refusal: String? get() = lastRefusal

    /**
     * Write down what a 201 or a refusal said.
     *
     * A SUCCESS CLEARS THE REFUSAL and a refusal keeps it, because the two are answers to the same
     * question at different moments: a fresh 201 proves there was room, and a client that kept
     * yesterday's sentence beside today's count would be showing a designer a ceiling they are not
     * at. This client never composes the sentence — see [dwVerbAllowanceRefusal].
     */
    fun learn(allowance: DwAiVerbAllowance, refusal: String?) {
        state = allowance
        lastRefusal = refusal
    }

    /** Forget everything. FOR SIGN-OUT, and for tests. See this object's last paragraph. */
    fun forget() {
        state = null
        lastRefusal = null
    }
}
