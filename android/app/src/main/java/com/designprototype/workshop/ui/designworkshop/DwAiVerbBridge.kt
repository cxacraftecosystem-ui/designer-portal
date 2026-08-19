package com.designprototype.workshop.ui.designworkshop

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * HOW THE AI VERBS REACH THE SERVER — the one seam between these three screens and the data lane.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A BRIDGE AND NOT A REPOSITORY HANDLE, WHICH IS THE SAME QUESTION [DwMediaBridge] ANSWERS.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The verb controls are drawn from two places that do not have the same dependencies —
 * [RichTextEditor], whose header states in capitals that it has no data layer and deliberately
 * wants none, and the media card inside [FieldRenderer] — and a control that worked in one and
 * silently did not in the other is the exact failure `DwDictationRun.repository` was written up
 * against. So the capability is passed down as a handle, like the media bridge and the reference
 * bridge beside it, and **null is an ordinary state meaning "this field cannot run them"**: a stage
 * rendered as a preview has no bridge and therefore draws no verb controls at all, rather than
 * drawing ones that fail when pressed.
 *
 * ── EVERY CALL ANSWERS WITH AN OUTCOME AND NEVER THROWS ─────────────────────────────────────────
 *
 * The three shapes a press can end in are [DwAiVerbOutcome.Produced], [DwAiVerbOutcome.Refused] and
 * [DwAiVerbOutcome.Offline], and the split is where it is for a reason worth stating: HTTP belongs in
 * the data lane, and a screen that had to read a status code would be the second place in this
 * application deciding what a 409 means. `DwDictationConsentRefused`, `DwDictationCapRefused` and
 * `DwDictationNotConfigured` are the precedent — three types rather than three status codes, because
 * "two things in this deployment answer 429 and they want opposite handling".
 *
 * **[DwAiVerbOutcome.Refused] CARRIES THE SERVER'S OWN SENTENCE AND THIS SCREEN PRINTS IT VERBATIM.**
 * Consent (`dictation_consent.gate_refusal`), the ceiling (`ai_verb_cap.cap_refusal`), the placement
 * law (`ai_layers.check_placement`) and the 503 for a server with no key are all field copy already,
 * each naming a next move that can actually work. The only sentences this client writes are the ones
 * in [DwAiVerbCopy], which no server ever sees because the point of them is that no request is made.
 *
 * ── AND WHY THERE IS NO `queue` ANYWHERE ON THIS HANDLE ─────────────────────────────────────────
 *
 * Every other write in this app banks itself in `OfflineOutbox` and drains later. A verb may not:
 * `ai_verb_cap.spend` counts every run that reached a provider INCLUDING a failure, so a run banked
 * in a courtyard and replayed three days later would be charged against a day the designer is not
 * having, over a workshop whose consent may have been withdrawn in between. [DwAiVerbOutcome.Offline]
 * is a refusal and not a deferral, and [DW_VERBS_NEED_A_CONNECTION] says so in as many words.
 */
@Immutable
class DwAiVerbBridge(
    /**
     * The id the SERVER knows this workshop by, or null while it exists only on this phone.
     *
     * **NOT THE ID THE SCREEN WAS NAVIGATED WITH.** A workshop created in a courtyard keeps its local
     * id in the route for the rest of the session, so the route param would send `dwlocal-…` into
     * `/design-workshops/{id}/ai-layers/proofread` and collect a bare 404 — which is what the web
     * shipped. This is the same value the stage screen already trusts for its stage PUT and already
     * publishes to every microphone on the stage.
     */
    val serverWorkshopId: String?,
    /** The workshop's recorded consent token — `DW_CONSENT_*`. Floors at NOT_RECORDED, never null. */
    val consent: String,
    /**
     * Whether this phone has a validated internet connection RIGHT NOW.
     *
     * A function and not a value, because it is the one input to the gate that changes between
     * composition and the press — a designer walks out of a courtyard mid-sentence — and a boolean
     * captured at composition would offer a control into a certain failure or withhold one that would
     * work. Called on the tap, on the main thread, like `conditionsNow()` beside it: it is a
     * `ConnectivityManager` property read and does no IO.
     */
    val isOnline: () -> Boolean,
    /** Correct spelling, grammar and punctuation in a passage, changing nothing else. */
    val proofread: suspend (passage: String) -> DwAiVerbOutcome,
    /**
     * Write a designer's terse note out into prose.
     *
     * **THIS SIGNATURE TAKES A PASSAGE AND MUST NEVER GAIN A LAYER PARAMETER.** Expanding is the one
     * verb that INVENTS sentences: over the designer's own shorthand it turns their note into their
     * prose and they are standing there to judge it; over an artisan's transcript it would put
     * invented words in a named person's mouth, in a document a ministry officer reads, and no
     * acceptance screen makes that safe because the person accepting is not the person being quoted.
     * The server refuses it three independent ways — `ai_layers.TEXT_ROOTED_KINDS`, `ai_verbs.expand`
     * constructing its own supplied-text source, and `AiExpandIn` having no `sourceLayerId` field at
     * all "so a client cannot even ask" — and this is the fourth.
     */
    val expand: suspend (note: String) -> DwAiVerbOutcome,
    /**
     * Translate a passage into [targetLanguage], producing a SIBLING that stands beside the original.
     *
     * No `sourceLanguage` parameter: the source is an OBSERVATION the run may already have made and
     * the server records what it detected — or `UNRECORDED` in that word — rather than defaulting it
     * to English. Asking a designer to assert it would be asking them to guess at provenance.
     */
    val translate: suspend (passage: String, targetLanguage: String) -> DwAiVerbOutcome,
    /** Describe a photograph or a video in one sentence, for the annexure and for a screen reader. */
    val caption: suspend (serverMediaId: String, language: String?) -> DwAiVerbOutcome,
    /** Timed captions for a recording or a video. See [DW_SUBTITLES_SECOND_UPLOAD_NOTE]. */
    val subtitles: suspend (serverMediaId: String) -> DwAiVerbOutcome,
    /** A person puts their name to this layer, and it becomes printable. */
    val accept: suspend (layerId: String) -> DwAiLayerDecisionOutcome,
    /**
     * Decline this layer. Soft, and it does not touch what the layer was made from.
     *
     * `ai_layers.deletion_plan` returns exactly one plan naming exactly one row, so declining a
     * transcript cannot take the recording with it — and the row survives as the only record that a
     * model proposed something and a person said no.
     */
    val decline: suspend (layerId: String) -> DwAiLayerDecisionOutcome,
    /**
     * One SUBTITLES layer written to a file this phone can hand to a player.
     *
     * **NOT GATED ON ACCEPTANCE, matching the route, which is deliberate and says so:** *"requiring
     * acceptance first would mean accepting subtitles nobody has watched, which is the opposite of
     * what acceptance is for."* This is the designer looking at what the model produced, in the only
     * form in which subtitles can actually be judged, which is played against the video.
     */
    val subtitleFile: suspend (layerId: String, format: DwSubtitleFormat, speakers: Boolean) -> DwSubtitleFileOutcome,
    /**
     * Which of this workshop's server media ids already carry a live SUBTITLES layer.
     *
     * **NULL IS "NOT KNOWN" AND MUST NEVER BE FLATTENED TO AN EMPTY SET.** An empty set says "none of
     * these has been subtitled", which is a confident wrong answer that invites a designer to spend a
     * paid second upload of audio they have already paid to upload once. The media row draws
     * [DwVerbGate.Waiting] until this answers and stays silent if it never does.
     */
    val subtitledMediaIds: suspend () -> Set<String>?,
)

/** The two subtitle files, and what each is actually for, so a designer picks by the player. */
enum class DwSubtitleFormat(val extension: String, val label: String) {
    /** What a phone gallery, VLC and every desktop player open, and what goes on an email. */
    SRT("srt", "SubRip (.srt) — a phone gallery, VLC, an email attachment"),

    /** What a browser's `<track>` element takes. Neither format is a superset of the other. */
    VTT("vtt", "WebVTT (.vtt) — a browser video player"),
}

/** What a press ended in. Three cases, and a screen must handle all three. */
sealed interface DwAiVerbOutcome {
    /**
     * The server ran the verb and wrote a layer. **NOTHING HAS BEEN APPLIED ANYWHERE.**
     *
     * `_finish_verb` puts `accepted: false` and `acceptanceRequired: true` on the wire rather than in
     * documentation, and the route says why: *"the client that just asked for this has words on
     * screen and is one tap from putting them in a report"*.
     */
    data class Produced(val run: DwAiVerbRun) : DwAiVerbOutcome

    /**
     * The server answered and said no, in its own words.
     *
     * [allowance] is present when the refusal carried the meter — a 429 does — so the surface can
     * update its countdown from the refusal instead of asking again.
     */
    data class Refused(val sentence: String, val allowance: DwAiVerbAllowance?) : DwAiVerbOutcome

    /** The request never reached anybody. A refusal and NOT a deferral — nothing has been queued. */
    data object Offline : DwAiVerbOutcome
}

/** What an accept or a decline ended in. */
sealed interface DwAiLayerDecisionOutcome {
    /** Done. [layer] is the row as it now stands, so a sheet can redraw from the server's copy. */
    data class Done(val layer: DwAiLayerView?) : DwAiLayerDecisionOutcome
    data class Refused(val sentence: String) : DwAiLayerDecisionOutcome
    data object Offline : DwAiLayerDecisionOutcome
}

/** What a subtitle download ended in. */
sealed interface DwSubtitleFileOutcome {
    /**
     * The bytes are on this phone under [fileName], shareable through the app's own FileProvider.
     *
     * **THE FILE NAME IS THE SERVER'S AND IS NEVER INVENTED.** `download_subtitles` writes
     * `subtitles-{layer}.speakers.srt` for the labelled file and `subtitles-{layer}.srt` for the
     * anonymised one, precisely so a designer holding both can tell them apart — and confusing those
     * two is how a ministry is emailed the version that attributes an artisan's words to a machine's
     * guess.
     */
    data class Saved(val shareUri: Uri, val mimeType: String, val fileName: String) : DwSubtitleFileOutcome
    data class Refused(val sentence: String) : DwSubtitleFileOutcome
    data object Offline : DwSubtitleFileOutcome
}

/**
 * The 201 from any of the five routes: the layer, and the meter as it stands after the run.
 *
 * THE ALLOWANCE TRAVELS ON THE SUCCESS AS WELL AS ON THE REFUSAL, which is the whole reason a client
 * need not spend a run to learn the ceiling — `ai_verb_cap.allowance_payload`'s own argument.
 */
@Immutable
data class DwAiVerbRun(
    val layer: DwAiLayerView,
    val allowance: DwAiVerbAllowance,
)

/**
 * `ai_verb_cap.allowance_payload`, key for key.
 *
 * **BOTH NUMBERS ARE NULL WHEN THERE IS NO CAP**, deliberately, "because 0 remaining and 'no ceiling'
 * must not look alike". Do not default either to zero anywhere; [dwAiVerbsSpent] is the only reader
 * entitled to an opinion about them.
 */
@Immutable
data class DwAiVerbAllowance(
    val limit: Int?,
    val used: Int?,
    val remaining: Int?,
    /** The SERVER's India-time date the count belongs to, e.g. "2026-08-19". Never this phone's. */
    val day: String?,
    val byVerb: Map<String, Int> = emptyMap(),
)

/**
 * One AI layer as these screens read it — `ai_layers.layer_payload`, key for key.
 *
 * **[textWithheld] IS THE MEDIA GATE ARRIVING HERE AND IT COVERS FOUR FIELDS, NOT ONE.** Who may read
 * a recording is decided per file and NOT by who may open the workshop, so when it is true the
 * preview, the character count, the payload and the text are all absent — and the flag is on every
 * payload rather than only the withheld ones, so a screen renders "you cannot read this one" from a
 * stated fact instead of inferring it from an empty preview.
 */
@Immutable
data class DwAiLayerView(
    val id: String,
    /** `ai_layers.LayerKind` — PROOFREAD, EXPANDED, TRANSLATION, CAPTION, SUBTITLES and the rest. */
    val kind: String,
    /** `ai_layers.AiTier` — which machine produced it. Null only for a row that recorded none. */
    val tier: String?,
    val source: DwAiLayerSource?,
    val provider: String?,
    val modelId: String?,
    val modelVersion: String?,
    val language: String?,
    /** Non-null only on a translation, where BOTH are recorded — "in English" is not a provenance. */
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val producedAt: String?,
    /** Rule 3 on the wire: a screen must never infer this from a null timestamp. */
    val accepted: Boolean,
    val acceptedAt: String?,
    val textWithheld: Boolean,
    /** The full text, present on the 201 because `_finish_verb` passes `include_text=True`. */
    val text: String?,
    /** The cue list of a SUBTITLES layer, already read out of `payload` by the data lane. */
    val cues: DwSubtitleCues?,
    /** The model's own uncalibrated confidence in a CAPTION, as it was stored. Never a measurement. */
    val selfReportedConfidence: String?,
)

/** Where a layer came from — `ai_layers.LayerSource` as the payload carries it. */
@Immutable
data class DwAiLayerSource(
    /** "TEXT", "LAYER" or "MEDIA". */
    val kind: String,
    val id: String?,
    /**
     * The words that were sent, for a supplied-text source.
     *
     * **THE EVIDENCE TRAVELS WITH THE LAYER**, and for this source kind there is no second request
     * that could fetch it — the passage exists only here. Withheld along with everything else when
     * the media gate says so, which for a supplied-text root it never does.
     */
    val text: String?,
)

/**
 * A stored SUBTITLES payload as a screen reads it — `subtitles.cues_payload`.
 *
 * [count] and [estimatedCues] are read from the stored WRAPPER where there is one rather than
 * recomputed, because the server stores them so a list can say "142 cues, 11 of them approximate"
 * without carrying every cue, and a client that recomputed would silently disagree with the annexure
 * the moment a payload was truncated anywhere.
 */
@Immutable
data class DwSubtitleCues(
    val count: Int,
    /**
     * How many boundaries were INVENTED rather than reported.
     *
     * ⚠ NOT RETROACTIVE, and `cues_payload` says so: a cue list stored before the key existed carries
     * no markers and reads as zero whether or not its boundaries were invented. Print it as a count
     * and never as a guarantee that the rest are exact.
     */
    val estimatedCues: Int,
    val durationSeconds: Double?,
    val language: String?,
    /**
     * Whether ANY cue carries a speaker label, and therefore whether the labelled file may be asked
     * for at all. A layer whose cues hold none refuses the flag rather than serving the same file.
     */
    val hasSpeakers: Boolean,
    val cues: List<DwSubtitleCue>,
)

/** One cue as `subtitles.Cue.payload` writes it. */
@Immutable
data class DwSubtitleCue(
    val start: Double,
    val end: Double,
    val text: String,
    /** The ENGINE'S GUESS at who was speaking, present only when it made one. Never a person's name. */
    val speaker: String?,
    /** True when this cue's boundary was interpolated rather than reported by the provider. */
    val estimated: Boolean,
)
