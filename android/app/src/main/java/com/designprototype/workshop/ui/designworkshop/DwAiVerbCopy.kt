package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_CONSENT_GRANTED
import com.designprototype.workshop.data.DW_CONSENT_REFUSED
import com.designprototype.workshop.data.DW_CONSENT_ROW_TITLE

/**
 * THE FIVE THINGS A DESIGNER CAN ASK A MODEL TO DO, AND EVERY SENTENCE THIS HANDSET SAYS BEFORE THE
 * REQUEST IS MADE.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE IS FOR: THE HALF OF THE FEATURE THAT CAN BE A PURE FUNCTION, SO IT IS ONE.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Everything here is decided without a network, a Context or a composition, which is what lets
 * `DwAiVerbCopyTest` assert on it. The three surfaces that draw these verbs — the prose panel, the
 * media row and the review sheet — share every rung and every sentence from here rather than each
 * computing its own, and the web's own account says why that mattered:
 *
 *   *"Three surfaces computed this ladder inline, in three nested ternaries, and the two properties
 *   that matter about it were therefore only checkable by reading all three: that the rungs are in
 *   the same ORDER … and that 'still reading' is DISTINGUISHABLE from 'go ahead'."*
 *
 * The countdown had the same history: `verbAllowanceRefusal`'s KDoc on the web records that
 * `verbsBlocked`, `AiVerbSelectionMenu` and `MediaAiVerbs` each carried their own copy of the same
 * forty words. There is one copy here, in [dwVerbAllowanceRefusal] and [dwAiVerbCountdown].
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THE REFUSALS A DESIGNER READS **AFTER** A PRESS ARE NOT IN THIS FILE AND MUST NOT BE ADDED TO IT.
 *
 * Consent (`dictation_consent.gate_refusal`), the ceiling (`ai_verb_cap.cap_refusal`), the layer law
 * (`ai_layers.check_placement`) and the 503 that means no key is configured are all sentences the
 * SERVER composes, each already naming a next move that can actually work. They are printed verbatim
 * — the same discipline `DwDictationConsentRefused` states for the dictation 409: *"[detail] IS THE
 * COPY … Composing our own here would mean choosing between those two states from a body that does
 * not name one."* What is authored here is only the states in which **no request is made at all**,
 * because no server ever sees those and there is therefore nobody else to write them.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * NOTHING HERE RUNS OFFLINE, AND [DW_VERBS_NEED_A_CONNECTION] IS THE PRIMARY STATE ON THIS CLIENT
 * RATHER THAN AN EDGE CASE. Every one of the five routes is a provider round trip the server makes
 * on this designer's behalf; there is no on-device runner for any of them (`_SERVER_TIER` is a module
 * constant and all five routes pass it). And a run SPENDS MONEY the moment it reaches a provider —
 * `_count_refused_run` counts a failure too, "because the credit is spent by the call" — so a verb
 * may not be queued in the outbox with the stage saves beside it. A run banked in a courtyard and
 * replayed three days later would be charged against a day the designer is not having, over a
 * workshop whose consent may have been withdrawn in between.
 */

/**
 * `ai_verbs.Verb` — what somebody ASKED for. It is the meter's label and the route's last path
 * segment, and it is deliberately NOT `ai_layers.LayerKind`: a kind describes what a stored row IS
 * and is printed as a heading in a government document, a verb describes what somebody asked for.
 *
 * Four of the five line up with a layer kind and one does not — the verb is [EXPAND] and the kind it
 * produces is `EXPANDED`. Do not use one where the other belongs.
 */
enum class DwAiVerb(
    /** The last path segment of `/design-workshops/{id}/ai-layers/…`, so nothing is lower-cased by hand. */
    val path: String,
    /** `ai_verbs.Verb.human`, so a sentence before the press and one after it call one act one name. */
    val human: String,
) {
    PROOFREAD("proofread", "proofreading"),
    EXPAND("expand", "expanding a note"),
    TRANSLATE("translate", "translation"),
    CAPTION("caption", "describing a photograph"),
    SUBTITLES("subtitles", "subtitling"),
}

/** The layer kind each verb produces, for the heading the review sheet titles itself with. */
fun dwLayerKindOf(verb: DwAiVerb): String = when (verb) {
    DwAiVerb.PROOFREAD -> "PROOFREAD"
    // THE ONE THAT IS NOT ITS OWN NAME. `ai_layers.LayerKind.EXPANDED`, from the verb `EXPAND`.
    DwAiVerb.EXPAND -> "EXPANDED"
    DwAiVerb.TRANSLATE -> "TRANSLATION"
    DwAiVerb.CAPTION -> "CAPTION"
    DwAiVerb.SUBTITLES -> "SUBTITLES"
}

/**
 * The longest passage a verb may be asked to work on.
 *
 * `ai_layers.MAX_SOURCE_TEXT_CHARS`, which `schemas.MAX_VERB_TEXT_CHARS` imports rather than repeats.
 * **Kept in step BY HAND on this client, which is the one thing a handset cannot import**, so it is
 * named once here and read everywhere.
 *
 * Checked before the press rather than after it, because the server's refusal has the argument in it
 * — a proofread of the first ten pages of a twelve-page note, recorded as a proofread of the note, is
 * a layer whose source is not what it says — and a designer who picks a stage-13 narrative and gets a
 * bare 422 after a round trip on one bar of signal learns only that the button is broken.
 */
const val DW_MAX_VERB_TEXT_CHARS: Int = 20_000

/** `ai_verbs.MAX_LANGUAGE_CHARS`, and the `max_length` on both language fields of `AiTranslateIn`. */
const val DW_MAX_VERB_LANGUAGE_CHARS: Int = 40

/**
 * How near the ceiling a countdown is drawn at.
 *
 * Three, matching the web: with ten left the number is noise, and with none left it is a refusal
 * rather than a warning.
 */
const val DW_AI_VERB_COUNTDOWN_FROM: Int = 3

// -------------------------------------------------------------------------------------------------
// The gate
// -------------------------------------------------------------------------------------------------

/**
 * WHETHER A VERB MAY BE OFFERED AT ALL, AS THREE STATES THAT CANNOT BE CONFUSED FOR ONE ANOTHER.
 *
 * ── WHY A SEALED TYPE AND NOT A `String?` ───────────────────────────────────────────────────────
 *
 * The web models this as `string | null` with `""` meaning "still reading, stay silent", and its own
 * KDoc records what that cost: *"`AiLayersPanel` fed the reading state into a truthiness ternary,
 * where `""` is falsy, and rendered live buttons during the IndexedDB read."* The Kotlin translation
 * of that defect is one `isNullOrBlank()`, one `?.takeIf { it.isNotBlank() }` or one
 * `refusal?.let { Text(it) }` away — the last of which draws an empty `Text` and a live control at
 * the same time. A three-case sealed type makes "a refusal with nothing to say" unrepresentable, so
 * the `when` is exhaustive and the compiler is the thing that keeps the states apart.
 */
sealed interface DwVerbGate {
    /** Nothing at this level stands in the way. A caller may go on to its own rungs. */
    data object Ready : DwVerbGate

    /**
     * A fact this surface needs has not answered yet. **INERT AND SILENT**: the control is disabled
     * and NO sentence is drawn.
     *
     * The floor answers here all fail closed — an unread consent reads NOT_RECORDED, an unread
     * subtitle list reads "not known" — so drawing their sentences before the read lands would flash
     * "nobody has been asked" over a workshop that has been asked, and a designer who reads that once
     * stops trusting the sentence.
     */
    data object Waiting : DwVerbGate

    /** A refusal to draw in place of the control. Never blank — that state is [Waiting]. */
    data class Refused(val sentence: String) : DwVerbGate
}

/**
 * THE PRE-PRESS LADDER FOR THE WORKSHOP, IN ORDER, SHARED BY EVERY SURFACE THAT DRAWS A VERB.
 *
 * ── THE ORDER, AND WHY EACH RUNG IS WHERE IT IS ─────────────────────────────────────────────────
 *
 *  1. **CONSENT**, first, because it is the only rung whose answer is about a person rather than
 *     about this device or this minute. A 409 and never a permission problem: the server's own
 *     comment says a 403 *"would be wrong in both directions here — this designer is entitled to run
 *     the verb, and a colleague of the same rank would be refused identically. What is not in a state
 *     to permit the send is the WORKSHOP."*
 *  2. **NO SERVER COPY**, second and deliberately BELOW consent. It catches the defect either way —
 *     the defect IS the granted case — but above the consent rung it would promise that the verbs
 *     "become available" after the next sync, which on a workshop whose recorded answer is REFUSED
 *     they will not. Underneath it, all four combinations get a sentence that is unconditionally true.
 *  3. **NO CONNECTION**, third, because it is the only rung that is about right now. Above the two
 *     workshop rungs it would tell a designer with no signal that signal is what is missing, on a
 *     workshop where a person deciding is what is missing.
 *
 * The ceiling is NOT here. It is last on every surface — see [dwVerbAllowanceRefusal] — because it is
 * the one rung whose answer this client may not have at all.
 *
 * ── THE DEFECT RUNG 2 EXISTS FOR, WHICH SHIPPED ON THE WEB ──────────────────────────────────────
 *
 * Every verb route is `/design-workshops/{id}/…` and `load_workshop_or_404` finds no row for a
 * workshop that has never been sent up, so each press answered a bare 404 — "Record not found", a
 * sentence written about a missing record rather than about an unsent workshop, naming no next move.
 * The consent rung cannot catch it and actively hides it: consent is authored ON THIS DEVICE with the
 * artisan standing in the courtyard, so a workshop that has never left the phone can perfectly well
 * read GRANTED. On this handset the same pair is published by the stage screen for the microphones —
 * `DwDictationRun.publishWorkshopConsent(consent, serverWorkshopId)` — and a local-only workshop
 * publishes a null id there for exactly this reason, costing dictation its own server rung.
 *
 * @param serverWorkshopId the id the SERVER knows this workshop by, or null while it exists only on
 *   this device. **Never the id the screen was navigated with**: a workshop created in a courtyard
 *   keeps its local id in the route for the rest of the session, and `remoteWorkshopIdOf` /
 *   `DwDictationRun.publishedWorkshop().serverId` are what resolve the two apart. A blank string is
 *   read as no id, which is `publishWorkshopConsent`'s own rule and not a new one.
 * @param consent the token off the workshop's recorded answer — `DW_CONSENT_*`. Anything this does
 *   not recognise gates, matching `dictation_consent.consent_of`, which fails closed for the stated
 *   reason: this is deciding whether to send an artisan's words to a third party, "where the only
 *   safe answer to 'I cannot tell' is no".
 * @param online what [android.net.ConnectivityManager] said a moment ago. Asked at composition and
 *   again at the press, because it is the one input here that can change between the two.
 */
fun dwVerbWorkshopGate(
    serverWorkshopId: String?,
    consent: String?,
    online: Boolean,
): DwVerbGate {
    val decision = (consent ?: "").trim().uppercase()
    if (decision != DW_CONSENT_GRANTED) return DwVerbGate.Refused(dwConsentNotGranted(decision))
    if (serverWorkshopId.isNullOrBlank()) return DwVerbGate.Refused(DW_WORKSHOP_NOT_ON_SERVER_YET)
    if (!online) return DwVerbGate.Refused(DW_VERBS_NEED_A_CONNECTION)
    return DwVerbGate.Ready
}

/**
 * Is the ceiling reached, as far as this client can tell WITHOUT asking?
 *
 * `remaining != null && remaining <= 0`, and nothing else. **A NULL ANSWERS FALSE**, which is the
 * line an implementer gets wrong by writing `?: 0`: `ai_verb_cap.allowance_payload` sends null for
 * both the limit and the remainder on an uncapped deployment, deliberately, *"because 0 remaining and
 * 'no ceiling' must not look alike"*. A client that withheld the capability because it could not
 * confirm a ceiling would take the feature away on exactly the deployments that have none.
 */
fun dwAiVerbsSpent(remaining: Int?): Boolean = remaining != null && remaining <= 0

/**
 * Today's ceiling, in the SERVER's words wherever it supplied them — the last rung on every surface.
 *
 * The fallback is reached only when a 201 or a 429 moved the numbers without carrying a sentence, and
 * it deliberately does NOT invent the zero-cap case. `cap_refusal` has its own sentence for a cap of
 * 0 — verbs turned off by an administrator — and its docstring says why: *"'You have used all 0'
 * reads as a bug, and somebody who has run nothing all morning being told they have used up an
 * allowance would reasonably conclude the app is broken."* That sentence is the server's to write,
 * and a second voice on one rule is what this whole feature is written to avoid.
 *
 * @param remaining `aiVerbsRemaining`. Null is "no ceiling", never "none left".
 * @param serverRefusal `ai_verb_cap.cap_refusal(allowance)` when a refusal carried it.
 */
fun dwVerbAllowanceRefusal(remaining: Int?, serverRefusal: String?): String? {
    if (!dwAiVerbsSpent(remaining)) return null
    val sent = serverRefusal?.trim()
    if (!sent.isNullOrEmpty()) return sent
    return "Today's runs of the writing and captioning models are used up on this account. " +
        "Dictation has its own separate allowance and is unaffected."
}

/**
 * THE COUNTDOWN, COMPOSED IN ONE PLACE. Null means draw nothing at all.
 *
 * **NULL FOR AN UNCAPPED DEPLOYMENT, AND THAT IS THE WHOLE POINT OF THE FUNCTION.** `Dictation.tsx`'s
 * existing guard is `dictationsRemaining !== null` and it transfers unchanged: "0 left" and "no
 * ceiling" must not look alike, which is exactly what `?: 0` makes them. The day is printed with the
 * number because it is the SERVER's India-time day and not this phone's — a handset whose clock is a
 * day out would otherwise show a count against a date the designer does not recognise, with nothing
 * on screen admitting whose date it is.
 */
fun dwAiVerbCountdown(remaining: Int?, day: String?): String? {
    if (remaining == null || remaining > DW_AI_VERB_COUNTDOWN_FROM) return null
    val runs = if (remaining == 1) "1 run" else "$remaining runs"
    val on = day?.trim().orEmpty()
    return if (on.isEmpty()) {
        "$runs of the writing and captioning models left today. Dictation has its own separate " +
            "allowance and is unaffected."
    } else {
        "$runs of the writing and captioning models left for $on. Dictation has its own separate " +
            "allowance and is unaffected."
    }
}

// -------------------------------------------------------------------------------------------------
// The sentences this client authors, because no server ever sends them
// -------------------------------------------------------------------------------------------------

/**
 * NOTHING TO WORK ON — the caret is in an empty paragraph, or in one with only spaces in it.
 *
 * It names the PARAGRAPH because that is this handset's unit and not the web's. See [DwAiVerbsPanel]
 * for the argument; the short of it is that a selection in this editor can never span two blocks, so
 * "the selection" and "the paragraph" are the same passage whenever the designer has not dragged a
 * shorter one, and a control that said "select some words first" on a phone with no comfortable
 * selection toolbar would be asking for a gesture that mostly does not happen.
 */
const val DW_NOTHING_TO_WORK_ON: String =
    "Put the caret in the paragraph you want worked on, or select part of one. These run over a " +
        "passage rather than over the whole field, and what is sent is what the layer records as its " +
        "source — so a later reader sees exactly this passage quoted as the evidence."

/** The selection or paragraph is longer than the server will take, with both numbers in it. */
fun dwPassageTooLong(chars: Int): String =
    "That passage is $chars characters and at most $DW_MAX_VERB_TEXT_CHARS can be sent. This is a " +
        "bound on the evidence rather than on the verb: a proofread of the first ten pages of a " +
        "twelve-page note, recorded as a proofread of the note, is a layer whose source is not what " +
        "it says. Select a shorter passage, or split the paragraph."

/**
 * THERE IS NO CONNECTION, SO THE VERB GENUINELY CANNOT HAPPEN — **AND NOTHING HAS BEEN QUEUED.**
 *
 * The second half is the half a designer on this handset will otherwise get wrong, and it is a
 * sharper problem here than on the web: every other write in this app banks itself in
 * `OfflineOutbox` and drains later, the stage screen says so on every save, and silence here invites
 * the reading that the run is waiting to be sent. It is not, and deliberately — see this file's
 * header for the two independent reasons. The last clause exists to stop somebody retyping a passage
 * that is perfectly safe: the words are in the draft on this device and are untouched.
 */
const val DW_VERBS_NEED_A_CONNECTION: String =
    "These run on the server, so they need a connection and this phone has none. Nothing has been " +
        "queued for later — a run spends real provider credit and counts against today's allowance, " +
        "so one replayed in three days' time would be charged against a day you are not having. Your " +
        "words are in the draft on this phone and are untouched; try again where there is signal."

/**
 * The workshop has not been cleared to send anything, so no verb can run.
 *
 * **NOT_RECORDED AND REFUSED GET DIFFERENT SENTENCES**, and collapsing them would be the defect
 * `dictation_consent.gate_refusal` keeps two strings apart to avoid: one is answered by asking the
 * artisan, and telling somebody to go and ask again when the answer is already on record is the sort
 * of instruction that teaches a designer to stop reading these messages.
 *
 * Both name the row that fixes it by the title it actually carries on this phone —
 * [DW_CONSENT_ROW_TITLE], read from the constant rather than retyped, so a reworded row cannot leave
 * this sentence pointing at a heading that is no longer there.
 */
fun dwConsentNotGranted(consent: String?): String {
    val token = (consent ?: "").trim().uppercase()
    if (token == DW_CONSENT_REFUSED) {
        return "This workshop's material may not be sent out — that is the answer on record — so " +
            "nothing here can be proofread, written out, translated, described or subtitled. " +
            "Nothing was sent, and your words are exactly as you left them. If the artisan has " +
            "since agreed, change that answer on this workshop's stage list, under " +
            "“$DW_CONSENT_ROW_TITLE”."
    }
    return "Nobody has recorded yet whether material from this workshop may be sent out, so nothing " +
        "here can be proofread, written out, translated, described or subtitled. Ask the artisan " +
        "and record their answer on this workshop's stage list, under " +
        "“$DW_CONSENT_ROW_TITLE” — until somebody does, this stays unavailable."
}

/**
 * THE WORKSHOP ITSELF HAS NEVER REACHED THE SERVER, so no verb has a record to run over.
 *
 * See [dwVerbWorkshopGate] for the defect this rung exists for and for why it sits BELOW the consent
 * rung rather than above it. It promises that the verbs become available after the next sync, which
 * is true only where sync is the one thing missing — which the ordering is what guarantees.
 */
const val DW_WORKSHOP_NOT_ON_SERVER_YET: String =
    "This workshop is still only on this phone, so there is nothing for a model to read — these run " +
        "on the server's copy and the server has never seen this one. Send it up from the workshop " +
        "list and they become available. Nothing you have written is at risk: it is in the draft " +
        "here, and no run has been queued, because a run spends provider credit against the day it " +
        "is made."

/** A photograph still only on this phone has no server id to send, which is a different refusal. */
const val DW_MEDIA_NOT_UPLOADED_YET: String =
    "This file has not reached the server yet, so there is nothing to send — the verb runs on the " +
        "server's copy. It goes up with the next sync, and you can describe it then."

/**
 * Said before the press on the one verb that costs an upload of bytes the archive already holds.
 *
 * The route's own docstring calls this "a defect rather than a design": ElevenLabs Scribe v2 and
 * Deepgram Nova-3 both already return timings and both discard them one line after parsing, so
 * nothing already in the archive can be subtitled without sending the audio again. On a handset the
 * warning matters more than it does in a browser — the second upload is over the designer's own
 * mobile data, in a district town, from a phone that has already paid once to upload the recording.
 */
const val DW_SUBTITLES_SECOND_UPLOAD_NOTE: String =
    "Subtitling sends this recording to a transcription engine again. The timings are the whole " +
        "point of it and nothing already stored has them, so even a recording this workshop has " +
        "already transcribed has to go up a second time — which costs an upload over this phone's " +
        "own connection and one run of today's allowance."

/**
 * THE ONE VERB THAT NEVER RUNS ON A DESIGNER'S OWN KEY, said out loud because no client can tell.
 *
 * Four of the five verbs pass `user_id=current_user.id` into `user_ai_keys.resolve`, so a designer's
 * own key is used when they have one that can do the task. `subtitle_ai_layer` calls
 * `ai.transcribe_timed_bytes(content, filename, mime, get_settings())`, and that function's signature
 * has no `user_id` parameter at all — so subtitles always run on the deployment's key. A backend
 * asymmetry rather than a client one, and nothing here changes it; saying so is the alternative to
 * leaving a designer with a fabricated impression of who paid for the run.
 */
const val DW_SUBTITLES_DEPLOYMENT_KEY_NOTE: String =
    "Subtitles always run on this server's own transcription key, even if you have supplied one of " +
        "your own — the other four verbs use yours when you have one. That is a limitation of the " +
        "server rather than a choice made here, and it means the cost of this run falls on the " +
        "organisation."

/**
 * The one target language the server refuses, with its own reasoning rather than a regex.
 *
 * `ai_layers._check_languages` refuses a translation INTO `multi` because a target is a CHOICE the
 * caller makes and not an observation; `multi` remains a perfectly real SOURCE language, since these
 * interviews code-switch mid-sentence. Null when the value is acceptable, so a caller can use the
 * return directly as the field's refusal.
 *
 * THERE IS DELIBERATELY NO CLOSED LIST OF LANGUAGES to choose from. This fleet works in nineteen and
 * several of them — Marwari, Garhwali — have no code to name, so a picker would refuse the exact
 * languages this system exists to record.
 */
fun dwTranslationTargetRefusal(value: String): String? {
    val token = value.trim()
    if (token.isEmpty()) {
        return "Name the language to translate into — a name or a code, such as “Odia”, " +
            "“or” or “English”."
    }
    if (token.lowercase() == "multi") {
        return "“multi” is something a recording can BE, not something a translation can " +
            "be INTO. It is a real answer for the language a passage came FROM — these interviews " +
            "code-switch mid-sentence — but a target is a choice somebody makes. Name the one " +
            "language you want it in."
    }
    if (token.length > DW_MAX_VERB_LANGUAGE_CHARS) {
        return "A language name here is at most $DW_MAX_VERB_LANGUAGE_CHARS characters — " +
            "“Odia (Kalahandi dialect)” fits comfortably."
    }
    return null
}

// -------------------------------------------------------------------------------------------------
// Which verb a file admits
// -------------------------------------------------------------------------------------------------

/**
 * The media verbs one stored file may be offered, mirroring `_VERB_MEDIA_TYPES` exactly.
 *
 *     IMAGE  ->  caption
 *     VIDEO  ->  caption AND subtitles
 *     AUDIO  ->  subtitles
 *     anything else (PDF, DOCUMENT) -> neither
 *
 * **OFFERED BY TYPE SO THIS CLIENT NEVER PRODUCES THAT 409**, which the server checks before any
 * bytes move precisely because the failure otherwise is expensive and unreadable: a caption run over
 * an audio file uploads a recording to a vision model, which answers with a parse error after the
 * credit is spent, and a designer reads "FAILED (HTTP 400)" about a file they picked correctly.
 *
 * Compared with [String.endsWith] for the reason the server states about the same comparison: the
 * column's stored form has varied, and a prefixed enum spelling must not silently match nothing. This
 * handset's own vocabulary is `DraftMedia.mediaType` — "IMAGE / VIDEO / AUDIO / PDF / DOCUMENT" — so
 * the bare tokens match today and a prefixed one would still match tomorrow.
 */
fun dwVerbsForMediaType(mediaType: String?): Set<DwAiVerb> {
    val stored = (mediaType ?: "").trim().uppercase()
    if (stored.isEmpty()) return emptySet()
    val verbs = mutableSetOf<DwAiVerb>()
    if (stored.endsWith("IMAGE") || stored.endsWith("VIDEO")) verbs += DwAiVerb.CAPTION
    if (stored.endsWith("AUDIO") || stored.endsWith("VIDEO")) verbs += DwAiVerb.SUBTITLES
    return verbs
}

// -------------------------------------------------------------------------------------------------
// Reading a cue list
// -------------------------------------------------------------------------------------------------

/**
 * `01:04:09.480` -> a caption reader's `1:04:09`, or `4:09` under an hour. Seconds are TRUNCATED and
 * never rounded: a cue that starts at 4.9 s starts at 4 s in the file, and a preview that said 5 s
 * would disagree with the .srt the designer is about to play against the video.
 */
fun dwSubtitleTimecode(seconds: Double): String {
    val whole = kotlin.math.floor(seconds.coerceAtLeast(0.0)).toLong()
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    val mm = if (hours > 0) minutes.toString().padStart(2, '0') else minutes.toString()
    val ss = secs.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}
