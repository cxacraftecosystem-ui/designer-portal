package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * **THE FIVE THINGS A DESIGNER CAN ASK A CLOUD MODEL TO DO, AND THE SHAPE THIS HANDSET ASKS IN.**
 *
 * Proofread, expand, translate, caption, subtitle. The browser has been able to do all five since
 * commit 6d6e55f (`frontend/lib/aiVerbs.ts`); this phone could do none of them. This file is the wire
 * half of the port: the request bodies, the 201, the layer a verb produces, the daily allowance, the
 * refusals, and the pre-press ladder that decides whether a control may be offered at all.
 *
 * ── EVERY KEY BELOW WAS COPIED OUT OF THE PYTHON, NOT REMEMBERED ────────────────────────────────
 *
 * The bodies are `schemas/design_workshops.AiProofreadIn` / `AiExpandIn` / `AiTranslateIn` /
 * `AiMediaVerbIn`; the 201 is `design_workshops._finish_verb`; the layer is
 * `ai_layers.layer_payload`; the allowance is `ai_verb_cap.allowance_payload`; the cue list is
 * `subtitles.cues_payload`. [DwIdentityOcrDto] one file over records what guessing costs: five keys
 * declared that the endpoint had never sent, `ignoreUnknownKeys` meant nothing threw, and a PERFECT
 * read of an identity card was reported to a designer as unreadable.
 *
 * **THE BODIES ARE `APIModel`, WHICH IS `extra="forbid"`.** A key this file invents is not ignored up
 * there — it is a 422 on every press, for every designer, until an app update. So the request classes
 * carry the declared field names and nothing else, and `ApiClient.json`'s `explicitNulls = false` is
 * what keeps an unset optional off the wire rather than sending it as an explicit null.
 *
 * ── FOUR RULES THIS FILE IS WRITTEN TO KEEP ─────────────────────────────────────────────────────
 *
 *  1. **NOTHING HERE RETURNS A VALUE SHAPED TO BE PUT IN A FIELD.** A verb's output is a LAYER that a
 *     named person accepts or declines, never a replacement for what the designer wrote. The server
 *     makes that true by construction — `LayerWritePlan` may only name a table in `WRITABLE_TABLES`
 *     and `DwStageEntry` is deliberately absent — and this side keeps it true by having nothing to
 *     call: there is no `applyVerbResult`, no "text to paste", and [dwExpandBody] takes a note and
 *     has no layer parameter at all, mirroring `AiExpandIn`, *"so a client cannot even ask"*.
 *  2. **THE REFUSALS AFTER A PRESS ARE THE SERVER'S OWN WORDS.** Consent
 *     (`dictation_consent.gate_refusal`), the ceiling (`ai_verb_cap.cap_refusal`), the layering law
 *     (`ai_layers.check_placement`) and the 503 for a deployment with no key all arrive as sentences
 *     that already name a next move. The only strings authored here are the PRE-PRESS ones, which no
 *     server ever composes because the point of them is that the request is never made — and they are
 *     transliterated from `frontend/lib/aiVerbs.ts` rather than reworded, so one rule keeps one voice
 *     across the two clients.
 *  3. **A WORKSHOP THAT EXISTS ONLY ON THIS DEVICE MAY NOT BE ASKED.** See [dwVerbGate] and the
 *     `require` in [dwVerbWorkshopId]. The web shipped this defect and review caught it: every press
 *     on a local draft answered a bare 404 "Record not found", a sentence about a missing record
 *     rather than about an unsent workshop, naming no next move.
 *  4. **NOTHING HERE WORKS OFFLINE AND NOTHING HERE MAY BE QUEUED.** Every verb is a provider round
 *     trip the server makes on this designer's behalf: `_SERVER_TIER` is a module constant and all
 *     five routes pass it, so there is no on-device runner to fall back to (see [DwTier2Verb] for
 *     what a handset model could produce and [DW_TIER2_NO_WRITE_PATH_SENTENCE] for why it still has
 *     nowhere to put it). And a verb SPENDS MONEY: `ai_verb_cap.spend` counts every run that reached
 *     a provider INCLUDING a failure, so a run banked in [OfflineOutbox] and replayed three days later
 *     would be charged against a day the designer is not having, over a workshop whose consent may
 *     have been withdrawn in between. Offline is this app's primary path, so the honest sentence is
 *     [DW_VERBS_NEED_A_CONNECTION] and it says in words that nothing was queued.
 */

// ---------------------------------------------------------------------------------------------
// The vocabulary
// ---------------------------------------------------------------------------------------------

/**
 * `ai_verbs.Verb` — what somebody ASKED for, which is the meter's label and the route's last path
 * segment.
 *
 * **NOT THE SAME VOCABULARY AS A LAYER KIND, and four of the five line up while one does not:** the
 * verb is `EXPAND` and the kind it produces is `EXPANDED`. `ai_verbs`' own module docstring keeps them
 * apart for a reason worth repeating here — a KIND describes what a stored row IS and is printed as a
 * heading in a government document, a VERB describes what somebody asked for and is what the daily
 * meter counts. [DwTier2Verb] is the third vocabulary in this package and answers a different question
 * again: which verbs a model on THIS PHONE could run.
 *
 * [human] is `Verb.human`, copied so the sentence a designer reads before the press and the one the
 * server sends after it call one act one name.
 */
enum class DwAiVerb(val path: String, val human: String) {
    PROOFREAD("proofread", "proofreading"),
    EXPAND("expand", "expanding a note"),
    TRANSLATE("translate", "translation"),
    CAPTION("caption", "describing a photograph"),
    SUBTITLES("subtitles", "subtitling"),
}

/**
 * The longest passage a verb may be asked to work on — `ai_layers.MAX_SOURCE_TEXT_CHARS`, which
 * `schemas.MAX_VERB_TEXT_CHARS` imports rather than repeats.
 *
 * KEPT IN STEP BY HAND, which is the one thing this file cannot import, so it is named once here and
 * read everywhere rather than written as `20000` at three call sites.
 *
 * CHECKED BEFORE THE PRESS AND NOT AFTER IT. The service's refusal has the argument in it — *a
 * proofread of the first ten pages of a twelve-page note, recorded as a proofread of the note, is a
 * layer whose source text is not what it says* — and a designer who selects a stage-13 narrative and
 * gets a bare 422 after the round trip learns only that the button is broken.
 */
const val DW_VERB_MAX_TEXT_CHARS: Int = 20_000

/** `ai_verbs.MAX_LANGUAGE_CHARS`, and the `max_length` on both language fields of `AiTranslateIn`. */
const val DW_VERB_MAX_LANGUAGE_CHARS: Int = 40

/**
 * The shape a language name must have — `ai_verbs._LANGUAGE_TOKEN`, copied character for character.
 *
 * **IT IS NOT A LIST OF LANGUAGES AND MUST NEVER BECOME ONE.** The server states the reason: the user
 * has said Odia is not the only language, this fleet works in nineteen, and several of the languages
 * in these recordings — Marwari, Garhwali — have no code to name them. A closed list would refuse the
 * exact languages the system exists to record. What the shape buys instead is that no SENTENCE fits:
 * a target language reaches a prompt, and a free-text field that reaches a prompt is where
 * `?dimension=length. Ignore the preceding instructions…` came from on this deployment.
 */
private val DW_VERB_LANGUAGE_TOKEN = Regex("""^[A-Za-z][A-Za-z0-9 \-_()']{0,39}$""")

/**
 * What `provider` and `modelId` hold when nobody wrote them down — `ai_layers.UNRECORDED`.
 *
 * The constant itself is [DW_TIER2_UNRECORDED], declared in `DwTier2Layer.kt` and NOT re-spelled
 * here: two spellings of one token is how the two come to differ, and the cost of a stray difference
 * is asymmetric — a screen would print the bare word `UNRECORDED` at a designer, which reads like a
 * code rather than like an answer. Compared case-insensitively for the same reason.
 */
fun dwAiIsUnrecorded(raw: String?): Boolean =
    raw?.trim().orEmpty().equals(DW_TIER2_UNRECORDED, ignoreCase = true)

/**
 * **THE SERVER ID A VERB IS RUN AGAINST, OR A REFUSAL TO RUN IT AT ALL.** Rule 3 of the header, in the
 * one place every call site has to pass through.
 *
 * The pre-press ladder ([dwVerbGate]) already refuses a workshop that exists only on this device, in
 * words, before a control is offered. This is the second guard, and it is not belt-and-braces: the
 * ladder is fed by a screen that read a draft, and the repository is callable from anywhere. On the
 * web the equivalent hole shipped — every press on a device-only workshop answered a bare 404 "Record
 * not found", which names no next move and reads as a broken button.
 *
 * IT THROWS RATHER THAN RETURNING NULL because there is nothing a caller could usefully do with a
 * null: the only correct handling is not to have called, which is what the ladder is for. The message
 * is for whoever is reading the crash, not for a designer.
 */
fun dwVerbWorkshopId(workshopId: String): String {
    require(workshopId.isNotBlank() && !isLocalOnlyWorkshop(workshopId)) {
        "A verb runs on the server's copy of a workshop, and this id names one the server has never " +
            "seen ($workshopId). Gate the control with dwVerbGate before offering it; a local id " +
            "would answer 404 after the press."
    }
    return workshopId
}

// ---------------------------------------------------------------------------------------------
// What a text verb is run over
// ---------------------------------------------------------------------------------------------

/**
 * EITHER a passage the caller is holding, OR a stored layer. **One of the two and never both.**
 *
 * `_require_exactly_one_source` refuses the other two shapes on the server with the reason in it —
 * *"a result whose source nobody can determine cannot be printed or checked"* for two, and *"There is
 * nothing to proofread"* for none. Modelled as a closed hierarchy rather than as two nullable fields
 * so that the 422 is unreachable from this client BY CONSTRUCTION, rather than by a check somebody
 * has to remember to write at each call site that builds a body.
 */
sealed interface DwVerbSource {
    /** Words the designer selected. They travel on the row as `sourceText`; there is no row to point at. */
    data class Passage(val text: String) : DwVerbSource

    /** An existing layer of this workshop — a transcript rung, a cleaned rung, a summary. */
    data class StoredLayer(val layerId: String) : DwVerbSource
}

// ---------------------------------------------------------------------------------------------
// The request bodies
// ---------------------------------------------------------------------------------------------

/**
 * `AiProofreadIn`. Exactly these three keys, because `APIModel` is `extra="forbid"`.
 *
 * Built through [dwProofreadBody] rather than constructed directly, so "exactly one source" is a
 * property of the type system here rather than of a reviewer's attention.
 */
@Serializable
data class DwProofreadBody(
    val text: String? = null,
    val language: String? = null,
    val sourceLayerId: String? = null,
)

/**
 * `AiExpandIn`. **TWO KEYS, AND THE ABSENCE OF `sourceLayerId` IS THE POINT — see [dwExpandBody].**
 *
 * [text] is not nullable here because it is not optional there: `min_length=1`.
 */
@Serializable
data class DwExpandBody(
    val text: String,
    val language: String? = null,
)

/** `AiTranslateIn`. `targetLanguage` is required; `sourceLanguage` is not. See [dwTranslateBody]. */
@Serializable
data class DwTranslateBody(
    val text: String? = null,
    val sourceLayerId: String? = null,
    val targetLanguage: String,
    val sourceLanguage: String? = null,
)

/** `AiMediaVerbIn`. Caption and subtitles both take it; subtitles ignore `language` entirely. */
@Serializable
data class DwMediaVerbBody(
    val sourceMediaId: String,
    val language: String? = null,
)

/**
 * One source, one optional language hint, and no way to express the 422.
 *
 * `language` on a proofread is a HINT TO THE MODEL and not a claim about the text — the route then
 * prefers the SOURCE LAYER's own recorded language over it, because the verb does not change the
 * language and the stored row is better evidence than a client's guess.
 */
fun dwProofreadBody(source: DwVerbSource, language: String? = null): DwProofreadBody = when (source) {
    is DwVerbSource.Passage -> DwProofreadBody(text = source.text, language = dwVerbLanguage(language))
    is DwVerbSource.StoredLayer ->
        DwProofreadBody(sourceLayerId = source.layerId, language = dwVerbLanguage(language))
}

/**
 * A designer's terse note, to be written out into prose.
 *
 * **THIS FUNCTION HAS NO LAYER PARAMETER AND MUST NEVER GAIN ONE.** It is not an omission and it is
 * not lost symmetry: expanding is the one verb that INVENTS sentences. Over the designer's own
 * shorthand it turns their note into their prose and they are standing there to judge it; over an
 * artisan's transcript it would put invented words in a named person's mouth, in a document a ministry
 * officer reads, and no acceptance screen makes that safe because the person accepting it is not the
 * person being quoted. The server refuses it in three independent places — `ai_layers.TEXT_ROOTED_KINDS`,
 * `ai_verbs.expand` constructing its own supplied-text source, and `AiExpandIn` having no such field
 * at all — the browser's `expandDesignWorkshopNote` is the fourth, and this is the fifth. Adding a
 * parameter here is a change to the plan, not a convenience.
 */
fun dwExpandBody(note: String, language: String? = null): DwExpandBody =
    DwExpandBody(text = note, language = dwVerbLanguage(language))

/**
 * A passage and the language to put it into. **The original is never touched; this produces a sibling.**
 *
 * `targetLanguage` IS REQUIRED AND `sourceLanguage` IS NOT, which is not an inconsistency: the target
 * is a CHOICE only the caller can make, while the source is an OBSERVATION the run may already have
 * made. Left out, the server records what the run detected, or the word `UNRECORDED` — never English
 * by default. And where the source is a stored layer, that row's own language wins over anything sent
 * here, because the row is a record of what a run detected and the body is a client's assertion about
 * somebody else's row.
 */
fun dwTranslateBody(
    source: DwVerbSource,
    targetLanguage: String,
    sourceLanguage: String? = null,
): DwTranslateBody {
    val target = targetLanguage.trim()
    val from = dwVerbLanguage(sourceLanguage)
    return when (source) {
        is DwVerbSource.Passage ->
            DwTranslateBody(text = source.text, targetLanguage = target, sourceLanguage = from)
        is DwVerbSource.StoredLayer ->
            DwTranslateBody(sourceLayerId = source.layerId, targetLanguage = target, sourceLanguage = from)
    }
}

/**
 * A blank language is NO language rather than an empty one.
 *
 * `clean_language` treats `""` and `"   "` as None and passes them through, so sending the empty
 * string would be identical in effect and different in the diff — it is dropped here, where it is
 * visible, rather than relied on up there.
 */
private fun dwVerbLanguage(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

// ---------------------------------------------------------------------------------------------
// What comes back
// ---------------------------------------------------------------------------------------------

/**
 * Where a layer's words came from — `layer_payload`'s `source` key.
 *
 * **`SUPPLIED_TEXT` IS NOT LIKE THE OTHER TWO.** `MEDIA` and `LAYER` are POINTERS: the evidence is a
 * row that still exists and a reader can open it, and [id] names it. `SUPPLIED_TEXT` is a COPY — the
 * words the caller sent travel ON the layer, in [text], because there is no row to point at. [id] is
 * null for it, deliberately: `layer_payload` writes `stored.id or None` with the note that an empty
 * string is *"the shape a client renders as a link to nothing"*.
 */
@Serializable
data class DwAiLayerSourceDto(
    /** `MEDIA`, `LAYER` or `SUPPLIED_TEXT`. A string, so a kind a newer server writes still decodes. */
    val kind: String = "",
    /** The `MediaFile` id or the parent layer's id, and NULL for a supplied-text source. */
    val id: String? = null,
    /** The words a proofread or an expansion was made FROM. Null on the pointer kinds, and null when withheld. */
    val text: String? = null,
)

/**
 * ONE LAYER, exactly as `ai_layers.layer_payload` writes it. **Every field defaulted.**
 *
 * The defaults are doing real work rather than saving a line, and it is [DwDictationAllowance]'s
 * stated rule applied to a response instead of to a stored row: `coerceInputValues` in [ApiClient]
 * falls back to a default when a non-null field arrives null, so a column that is NOT NULL on the
 * server but null on one malformed row degrades to a blank string instead of failing the whole decode
 * — and with it the screen the designer is standing in front of.
 *
 * [textWithheld] IS ON EVERY PAYLOAD RATHER THAN ONLY ON THE WITHHELD ONES, so a client renders "you
 * cannot read this one" from a stated fact rather than inferring it from an empty preview. It covers
 * FOUR keys and not one: [preview], [textChars], [payload] and [text] all go, and the PROVENANCE
 * stays — which tier, which model, who accepted it — because none of that is the recording's content
 * and all of it is what a reviewer opens the screen for. Who may read a recording is decided PER
 * MEDIA FILE and not by who may open the workshop; a `DesignWorkshopViewer` grant carries stage
 * writes and says nothing about media, so the two sets genuinely differ.
 */
@Serializable
data class DwAiLayerDto(
    val id: String = "",
    val designWorkshopId: String = "",
    /** `ai_layers.LayerKind`. A STRING, so a kind written by a newer build decodes instead of throwing. */
    val kind: String = "",
    /** `TIER_1` / `TIER_2` / `TIER_3` — which MACHINE produced it. Not a ranking. See [DwAiTier]. */
    val tier: String = "",
    val source: DwAiLayerSourceDto? = null,
    /** NOT NULL on the server; may be the literal `UNRECORDED`. Never render it raw — see [dwAiIsUnrecorded]. */
    val provider: String = "",
    /** NOT NULL on the server; may be the literal `UNRECORDED`. */
    val modelId: String = "",
    /** The checkpoint or dated version where the provider publishes one. Null = nobody wrote it down. */
    val modelVersion: String? = null,
    /** May be the literal `multi`, which is a real answer for a code-switched interview and not a placeholder. */
    val language: String? = null,
    /**
     * The pair a TRANSLATION records, null on every other kind — and SENT UNCONDITIONALLY, which is
     * why they are declared here rather than read out of [payload].
     *
     * `layer_payload`'s own comment gives the reason: *"a client that has to look at `kind` before it
     * knows whether a key exists is a client that will one day read the wrong branch"*. [sourceLanguage]
     * may be `UNRECORDED` (the run detected nothing) and may be `multi`; [targetLanguage] can never be
     * `multi`, because a target is a choice somebody made rather than an observation.
     */
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    /**
     * When the MODEL ran, if anybody recorded it — deliberately NOT defaulted to the row's creation
     * time on the server, since a transcript the queue produced last March and registered today would
     * otherwise carry today's date as a statement about when the model ran.
     */
    val producedAt: String? = null,
    /** When the ROW appeared. A different question from [producedAt], and always answerable. */
    val createdAt: String? = null,
    val createdById: String? = null,
    /**
     * Rule 3 on the wire. An explicit boolean beside the nullable timestamp, so a client renders "not
     * accepted" from a stated fact rather than inferring it from a null that could equally mean
     * "accepted, timestamp missing" — and only one of those two may be printed in a report.
     */
    val accepted: Boolean = false,
    val acceptedAt: String? = null,
    /** A user id and NOT a name; the endpoint resolves no accounts. */
    val acceptedById: String? = null,
    /** Null rather than 0 when the text is withheld: 0 would say "there is nothing to read", which is false. */
    val textChars: Int? = null,
    /** Up to 280 characters of the opening, whitespace collapsed onto one line. */
    val preview: String? = null,
    /** The principal content of TAGS / METADATA / STRUCTURED_TEXT, and the cue list of SUBTITLES. */
    val payload: JsonElement? = null,
    /** See the class docstring: this account may not read the recording at the foot of this chain. */
    val textWithheld: Boolean = false,
    /** A soft delete: a declined suggestion stays on record as a declined suggestion. */
    val deletedAt: String? = null,
    /** Present when the caller asked for it AND the text is not withheld. Always present on a verb's 201. */
    val text: String? = null,
)

/**
 * What all five verb routes answer with — `_finish_verb`.
 *
 * [accepted] AND [acceptanceRequired] ARE ON THE WIRE RATHER THAN IN DOCUMENTATION, and the route
 * says why: *"the client that just asked for this has words on screen and is one tap from putting
 * them in a report"*. There is no shape of this response in which a fresh layer is already accepted;
 * the defaults say so, and a screen that hides them is hiding rule 3.
 *
 * THE ALLOWANCE RIDES ON THE 201 as well as in the refusal, which is the whole reason the ceiling is
 * not merely a 429: a client that can learn the ceiling only by being refused has to spend a run to
 * learn it, and a run is a provider call somebody pays for.
 */
@Serializable
data class DwAiVerbResultDto(
    val layer: DwAiLayerDto = DwAiLayerDto(),
    val accepted: Boolean = false,
    val acceptanceRequired: Boolean = true,
    /** The daily ceiling, or null when this deployment sets none. **0 is a real setting: verbs are off.** */
    val aiVerbsLimit: Int? = null,
    /** Runs that reached a provider today, across every verb — including the ones that then failed. */
    val aiVerbsUsed: Int = 0,
    /** What is left today, or null when uncapped. **Never conflate a null with a zero.** */
    val aiVerbsRemaining: Int? = null,
    /** The SERVER's India-time date the count belongs to, e.g. "2026-08-19". The freshness key. */
    val aiVerbDay: String? = null,
    /** `{VERB: count}` — the breakdown, ordered by the server as count then name. */
    val aiVerbsByVerb: Map<String, Int> = emptyMap(),
)

/** `GET /design-workshops/{id}/ai-layers` — every layer this workshop's material has produced. */
@Serializable
data class DwAiLayerListDto(
    val items: List<DwAiLayerDto> = emptyList(),
    val total: Int = 0,
    /**
     * How many rows here are accepted AND NOT DECLINED — which is not the same as counting the
     * accepted rows on screen. The two differ the moment `includeDeleted` is on: a layer accepted and
     * afterwards declined still carries its `acceptedAt`, because deletion clears no acceptance, and
     * the report will not print it. It is narrowed by `kind` exactly as the list is.
     */
    val accepted: Int = 0,
)

/** One acceptance or withdrawal — `ai_layers.decision_payload`. [actorId] is an id and never a name. */
@Serializable
data class DwAiLayerDecisionDto(
    val id: String = "",
    val layerId: String = "",
    /** `ACCEPTED` or `WITHDRAWN`. */
    val decision: String = "",
    val note: String? = null,
    val actorId: String = "",
    val createdAt: String? = null,
)

/**
 * The answer to accept and to unaccept: the layer's new state, AND its whole history.
 *
 * BOTH KEYS AND NOT ONE, and the route says why the log is in the response at all — *"the audit being
 * visible in the response is what stops a client rendering acceptance as a checkbox"*.
 */
@Serializable
data class DwAiLayerDecisionResultDto(
    val layer: DwAiLayerDto = DwAiLayerDto(),
    val decisions: List<DwAiLayerDecisionDto> = emptyList(),
)

/**
 * `AiLayerDecisionIn` — why a person accepted a layer, or why they took their name off it.
 *
 * Optional either way, and the schema argues for the asymmetry: accepting needs no explanation ("I
 * read it and it is right" is the whole message), but a WITHDRAWAL usually has a reason worth keeping,
 * and that reason is what stops the same layer being re-accepted by somebody who was not told.
 */
@Serializable
data class DwAiLayerDecisionBody(
    val note: String? = null,
)

// ---------------------------------------------------------------------------------------------
// The daily allowance
// ---------------------------------------------------------------------------------------------

/**
 * **THIS DESIGNER'S DAILY ALLOWANCE FOR THE WRITING AND CAPTIONING MODELS, AS THIS PHONE LAST HEARD
 * IT.** A separate ceiling from dictation's, and the sentences on both sides say so.
 *
 * ── MIRRORED TO DISK, WHICH IS WHERE THIS DIVERGES FROM THE BROWSER, DELIBERATELY ───────────────
 *
 * `frontend/lib/aiVerbs.ts` holds the numbers IN FLIGHT ONLY and argues the case: a field LAPTOP is
 * shared, `AuthProvider.logout` clears the token and deliberately nothing else, so a retained count
 * would follow the previous designer's account into the next one's session.
 *
 * THIS HANDSET ALREADY ANSWERED THAT QUESTION THE OTHER WAY FOR DICTATION, and the answer transfers
 * rather than being re-argued: [DwDictationAllowance] mirrors to SharedPreferences, KEYED BY USER ID,
 * precisely because a field phone is handed between designers — [CarryContextStore] keys every row by
 * user for the same stated reason. So the browser's objection is answered by the key rather than by
 * refusing to store, and what the store buys is what it buys for dictation: the refusal happens
 * BEFORE the press, on a phone that has been swiped away, instead of after a round trip on a
 * connection that in a district town is scarcer than the provider credit the ceiling is about.
 *
 * NOTHING HERE ENFORCES ANYTHING. The count that decides is a row in the server's usage table; this
 * is a copy, and a copy defeated by a swipe-away would be a ceiling in name only. What it decides is
 * whether to spend the round trip.
 *
 * ── AND THE ONE PLACE THIS PHONE IS BLINDER THAN THE BROWSER, SAID PLAINLY ───────────────────────
 *
 * **THERE IS NO PRE-FLIGHT ROUTE. `GET /design-workshops/ai-verb-allowance` DOES NOT EXIST** — the
 * string `ai-verb-allowance` does not occur anywhere under `backend/app/`; there is no route, no
 * handler and no mention. The browser calls it anyway, documents that every deployment answers 404,
 * and degrades. Dictation HAS such a route (`GET /design-workshops/dictation-allowance`) and
 * [WorkshopRepository.refreshDictationAllowance] uses it. The consequence for the verbs, which is not
 * hidden: **a handset that has learned nothing yet cannot refuse the FIRST run of a day before making
 * it.** It learns the numbers from that run's own 201, or from the 429 that refuses it, and every
 * press after that is decided here without a request. That is one round trip per designer per day,
 * not one per press, and it is bounded and self-clearing — which is the shape of failure to prefer
 * over inventing an allowance nobody was told. When the route lands, one method beside
 * `refreshDictationAllowance` is the whole client change.
 *
 * ── THE DAY BOUNDARY, AND WHY THIS FAILS OPEN WHERE CONSENT FAILS CLOSED ───────────────────────
 *
 * The boundary is the SERVER's India-time day and is [dwDictationIstDay] — **the same function, not a
 * second one**. It is named for dictation because that is what needed it first, and a second reckoning
 * of the boundary, even a correct one, is a second definition: the first day the two disagreed a
 * designer would hold two allowances or none. A stored record whose day is not today resolves to NOT
 * SPENT, exactly as dictation's does and for its argued reason — a stale allowance costs one refused
 * press per designer per day boundary, while a phone that failed closed at the wrong midnight would
 * silently withhold a capability that has already been paid for.
 */
@Serializable
data class DwAiVerbAllowance(
    /** Whose allowance this is. Repeated inside the payload as well as being the storage key. */
    val userId: String = "",
    /** The day the SERVER named, or — for a refusal learned from a 429 — this phone's reckoning of it. */
    val day: String = "",
    /**
     * The configured ceiling, or null for "this phone has not been told one".
     *
     * NULL IS NOT ZERO AND NOT "UNCAPPED". The server sends null when the deployment is uncapped and
     * 0 when these verbs are switched off entirely, and a phone that has never had an answer has
     * neither fact. All three read differently to a designer, so all three are kept apart.
     */
    val limit: Int? = null,
    /** How many are left, or null when there is no ceiling to count down from. 0 means refuse. */
    val remaining: Int? = null,
    /** `{VERB: count}` for today, so a countdown can say what the runs went on without a round trip. */
    val byVerb: Map<String, Int> = emptyMap(),
)

/**
 * What a surface is told about the ceiling: whether it is spent, the number to name, and what is left.
 *
 * SEVERAL ANSWERS OUT OF ONE STALENESS RULE, on purpose — [DwDictationCapView]'s argument, which is
 * that asking "is it spent" and "what is the limit" as separate functions is two readings of one
 * freshness test, and the day they drift the phone prints yesterday's ceiling beside today's refusal.
 */
data class DwAiVerbCapView(
    val spent: Boolean,
    val limit: Int?,
    val remaining: Int?,
    /** Today's breakdown by verb, empty when nothing fresh is stored. */
    val byVerb: Map<String, Int> = emptyMap(),
)

/** Nothing known: not spent, and no number to name. The honest answer, and the safe one. */
private val DW_AI_VERB_CAP_UNKNOWN = DwAiVerbCapView(spent = false, limit = null, remaining = null)

/**
 * Resolve a stored allowance against who is signed in and what day it is here.
 *
 * Pure, so the four ways it can go wrong are assertable on a desktop JVM with no handset, and they are
 * [dwDictationCapView]'s four because they are the same four facts:
 *
 *  1. NOTHING STORED — no verb has succeeded or been refused on this phone yet. Not spent.
 *  2. NOBODY SIGNED IN — read as unknown rather than as the last account's allowance.
 *  3. A DIFFERENT DESIGNER'S ROW. The cap is per designer; a phone handed over at lunch must not
 *     refuse the afternoon's designer, and must not print the morning's ceiling to them either.
 *  4. A DIFFERENT DAY. Stale, so not spent — see the type docstring on why this direction is open.
 *
 * With all four passed, "spent" is `remaining <= 0`. A null `remaining` is an uncapped deployment and
 * can never be spent, which is the reading the obvious `?: 0` gets exactly backwards.
 */
fun dwAiVerbCapView(
    stored: DwAiVerbAllowance?,
    userId: String?,
    today: String,
): DwAiVerbCapView {
    if (stored == null || userId.isNullOrBlank()) return DW_AI_VERB_CAP_UNKNOWN
    if (stored.userId != userId || stored.day.isBlank() || stored.day != today) {
        return DW_AI_VERB_CAP_UNKNOWN
    }
    val remaining = stored.remaining
        ?: return DwAiVerbCapView(
            spent = false,
            limit = stored.limit,
            remaining = null,
            byVerb = stored.byVerb,
        )
    return DwAiVerbCapView(
        spent = remaining <= 0,
        limit = stored.limit,
        remaining = remaining,
        byVerb = stored.byVerb,
    )
}

/**
 * The allowance one verb's 201 reported, or null when it reported none.
 *
 * **A SERVER THAT SENT NO `aiVerbDay` IS NOT AN ALLOWANCE.** The day is what the whole freshness rule
 * turns on, so a payload without one — an older deployment that predates the cap, or a response that
 * simply never carried it — must leave whatever is stored alone rather than overwrite it with a record
 * that can never match a day. The same rule `dwDictationAllowanceOf` states one file over.
 */
fun dwAiVerbAllowanceOf(dto: DwAiVerbResultDto, userId: String?): DwAiVerbAllowance? {
    val day = dto.aiVerbDay?.trim().orEmpty()
    if (day.isEmpty() || userId.isNullOrBlank()) return null
    return DwAiVerbAllowance(
        userId = userId,
        day = day,
        limit = dto.aiVerbsLimit,
        remaining = dto.aiVerbsRemaining,
        byVerb = dto.aiVerbsByVerb,
    )
}

/**
 * The refusal a 429 leaves behind, keeping a ceiling this phone was already told about TODAY.
 *
 * **THE VERB ROUTES' 429 CARRIES NO NUMBERS AT ALL, WHICH IS THE DIFFERENCE FROM THE 201 AND IS
 * MEASURED RATHER THAN ASSUMED.** `_verb_gate` raises `HTTPException(status_code=429, detail=spent)`
 * where `spent` is `ai_verb_cap.cap_refusal(...)` — a plain string. So the body is `{"detail": "…"}`
 * and nothing else: no `aiVerbDay`, no `aiVerbsLimit`. The day is therefore THIS PHONE's reckoning of
 * today rather than the server's, which is the one place the mirror holds a date nobody sent it, and
 * the cost of a wrong device clock is that the record never matches and every press pays its round
 * trip — the behaviour before the mirror existed, no worse.
 *
 * `remaining = 0` is the whole refusal. The limit and the breakdown are carried over from [previous]
 * only when that record is this designer's and about this same day, because a ceiling learned
 * yesterday is not a fact about today's.
 */
fun dwAiVerbCapSpentRecord(
    previous: DwAiVerbAllowance?,
    userId: String,
    today: String,
): DwAiVerbAllowance {
    val fresh = previous?.takeIf { it.userId == userId && it.day == today }
    return DwAiVerbAllowance(
        userId = userId,
        day = today,
        limit = fresh?.limit,
        remaining = 0,
        byVerb = fresh?.byVerb.orEmpty(),
    )
}

/**
 * How near the ceiling a countdown should be drawn at.
 *
 * Three, matching the browser's `AI_VERB_COUNTDOWN_FROM` and for its reason: with ten left the number
 * is noise, and with none left it is a refusal rather than a warning. Three is the point at which a
 * designer can still change what they do about it.
 */
const val DW_AI_VERB_COUNTDOWN_FROM: Int = 3

/**
 * Where the mirror lives between two presses, and between two runs of the app.
 *
 * SYNCHRONOUS BY DESIGN and SharedPreferences for [DwDictationAllowanceStore]'s reason: the gate is
 * consulted as a control is composed, on the main thread, and a suspending read there would either
 * block it or arrive a frame after the decision that needed it.
 *
 * ONE ROW PER DESIGNER AND NOT ONE PER DAY. Yesterday's row is of no use to anybody — the server holds
 * the history and this copy exists only to answer "may I skip the round trip right now" — so a new day
 * simply overwrites it. Nothing to sweep, no expiry to schedule.
 *
 * ITS OWN PREFERENCES FILE, not a second key inside dictation's. Two ceilings that are deliberately
 * separate on the server, kept in one file, is how a clear of one comes to clear the other.
 */
object DwAiVerbAllowanceStore {

    private const val PREFS = "dw_ai_verb_allowance"
    private const val KEY_PREFIX = "allowance:"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * This designer's stored allowance, or null.
     *
     * A ROW THAT WILL NOT PARSE IS DELETED, which is the right recovery here in a way it never is for
     * a draft: this is a COPY of something the server still holds, so throwing it away costs one round
     * trip to learn again. `DwReferenceStore` states the same rule in the same words, and adds the
     * clause that matters — *"a draft would never be treated this way"*.
     */
    fun read(context: Context, userId: String?): DwAiVerbAllowance? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences(context).getString(key(userId), null) ?: return null
        val row = runCatching {
            json.decodeFromString(DwAiVerbAllowance.serializer(), raw)
        }.getOrNull()
        if (row == null || row.userId != userId) {
            clear(context, userId)
            return null
        }
        return row
    }

    fun write(context: Context, allowance: DwAiVerbAllowance) {
        if (allowance.userId.isBlank()) return
        preferences(context).edit()
            .putString(
                key(allowance.userId),
                json.encodeToString(DwAiVerbAllowance.serializer(), allowance)
            )
            .apply()
    }

    fun clear(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) return
        preferences(context).edit().remove(key(userId)).apply()
    }

    private fun key(userId: String) = "$KEY_PREFIX$userId"
}

// ---------------------------------------------------------------------------------------------
// The refusals a press can come back with
// ---------------------------------------------------------------------------------------------

/**
 * **THIS DEPLOYMENT CANNOT RUN THIS VERB AT ALL** — a 503 whose body is FastAPI's, carrying the
 * server's own sentence.
 *
 * `VerbUnavailable` is its own class up there because it is its own status code, and the route
 * explains the choice: a 200 with empty output reads as "the model had nothing to say", which sends a
 * designer off to rewrite a perfectly good note, and the person who can fix it is an administrator
 * rather than them.
 *
 * **IT IS PER VERB AND MUST NOT BE REMEMBERED AS ONE FACT ABOUT THE SERVER**, which is why [verb] is
 * on the type. The four text-and-vision verbs fail for want of an OpenAI or Gemini key; subtitles fail
 * for want of a provider that returns TIMINGS — a deployment with only an OpenAI key can proofread all
 * day and cannot subtitle at all, because that rung is asked for `response_format=json`, which carries
 * no timings. A single "the server has no AI" flag, of the kind [DwDictationRun.serverRouteUnavailable]
 * keeps for dictation, would retire four working verbs on the first failed subtitle.
 *
 * A 503 WITHOUT a FastAPI `detail` is NOT this: it came from the gateway in front of the origin —
 * [ApiClient] documents 502/503/504 as what CloudFront answers when this origin is slow — means only
 * "not now", and arrives as an ordinary [DwAiVerbRefused].
 */
class DwAiVerbNotConfigured(val verb: DwAiVerb, val detail: String) : Exception(detail)

/**
 * A 429 FROM A VERB ROUTE — two different things, told apart by ONE key.
 *
 *  * **THE DAILY CAP.** This designer's allowance for the server's India-time day is spent. It will
 *    not clear until midnight IST, so it is worth REMEMBERING: otherwise every control on the stage
 *    spends its own round trip to be told the same thing.
 *  * **`app/scale/rate_limit.py`**, the courtesy backstop in front of the whole API — flag-gated off
 *    by default, and its own docstring calls it *"NOT a security control"*. Its refusal is about THIS
 *    INSTANT: it carries `retryAfterSeconds` and says wait a moment. Remembering that one would
 *    withdraw all five verbs for the rest of the day over a burst of taps.
 *
 * **TOLD APART BY SHAPE AND NOT BY ENGLISH**, and here there is only one key to look for rather than
 * dictation's two: `_verb_gate`'s 429 carries a plain string `detail` and no allowance keys at all,
 * while the limiter's carries `retryAfterSeconds`. Matching prose would break the first time somebody
 * reworded a sentence, and this repository has already been bitten by matching a provider's message
 * rather than its status ([dwDictationServerAnswerSentence]).
 *
 * **A BODY WITH NEITHER KEY IS TREATED AS THE CAP**, which is [DwDictationCapRefused]'s decision and
 * its argument transfers exactly: read as the cap, a mistake costs the five verbs until midnight IST
 * and says so in words; read as transient, a mistake spends a round trip per press for the rest of the
 * day. The bounded, visible, self-clearing error is the one to prefer.
 */
class DwAiVerbCapRefused(
    /** The server's own sentence for the person holding the phone, or null if the body carried none. */
    val detail: String?,
    /** The limiter's own key. Present means this was the courtesy backstop, not the cap. */
    val retryAfterSeconds: Int?,
) : Exception(detail) {
    /**
     * True only for a refusal that says WHEN to try again — the courtesy backstop, never the cap.
     *
     * The cap does not answer "in n seconds", because what it is waiting for is a calendar day.
     */
    val transientThrottle: Boolean get() = retryAfterSeconds != null
}

/**
 * **EVERY OTHER REFUSAL A VERB ROUTE MAKES, CARRYING THE SERVER'S SENTENCE AND CLASSIFYING NOTHING.**
 *
 * ── WHY THERE IS NO `DwAiVerbConsentRefused`, WHICH IS THE ONE ASYMMETRY WITH DICTATION ─────────
 *
 * [DwDictationConsentRefused] exists because `POST /{id}/dictate` answers 409 for one thing that
 * matters — the consent gate — plus one improbable other (an admin dictating into a soft-deleted
 * workshop). A VERB ROUTE ANSWERS 409 FOR FOUR DIFFERENT STATES, established by reading them:
 *
 *  * `load_workshop_or_404(..., for_edit=True)` — this workshop is deleted, restore it first;
 *  * `_verb_gate` — the consent question is NOT_RECORDED or REFUSED;
 *  * `_verb_source_layer` — that layer holds structured data rather than prose, so there is no
 *    passage to work on;
 *  * `_verb_source_media` — a caption needs a photograph and that file is audio (or the reverse for
 *    subtitles), refused BEFORE any bytes are sent so that nothing is spent.
 *
 * **The body carries no discriminator, so a client that named the 409 "consent" would tell a designer
 * who picked the wrong file to go and ask an artisan a question that is already on record** — which is
 * precisely how somebody learns to stop reading these messages. So the code is carried and the
 * sentence is shown; every one of those four is field copy that names its own next move.
 *
 * [status] is on the type for the callers that legitimately branch on it — a 403 is about the ACCOUNT
 * (not a designer, or the media gate refusing a layer whose recording they may not read) and a 404
 * means the workshop or the layer is not there — never for composing a sentence, which is the server's
 * job. [detail] is nullable and stays null rather than being papered over: a 409 whose body was
 * rewritten by something in between carries no sentence, and inventing one would be inventing a state.
 */
class DwAiVerbRefused(
    val status: Int,
    val detail: String?,
) : Exception(detail ?: "The server refused this run (HTTP $status).")

// ---------------------------------------------------------------------------------------------
// The pre-press ladder
// ---------------------------------------------------------------------------------------------

/**
 * Everything the pre-press ladder is allowed to know. Facts only — no Android types, no repository.
 *
 * The same discipline [DwDictationConditions] keeps and for its reason: this file DECIDES and never
 * READS, so every rung is assertable on a desktop JVM.
 */
data class DwVerbConditions(
    /**
     * The draft has been read off disk, so [consent] and [workshopOnServer] are answers rather than
     * floors. False while the read is in flight.
     */
    val draftRead: Boolean,
    /**
     * **THIS WORKSHOP HAS A ROW ON THE SERVER.** `remoteWorkshopIdOf(draft) != null`, computed by the
     * caller and passed in as one boolean.
     *
     * NOT `!isLocalOnlyWorkshop(routeId)`, and the difference is a live defect on the other client: a
     * workshop that HAS been up keeps its `local-…` id on this device, so gating on the id alone would
     * withhold working verbs and say something false about a workshop the server holds.
     * [remoteWorkshopIdOf] consults both `remoteId` and the workshop id, which is why it is the twin
     * to use.
     */
    val workshopOnServer: Boolean,
    /** This workshop's answer to "may its material be sent to a third party". The floor is NOT_RECORDED. */
    val consent: DwTier3Consent,
    /** [ConnectivityObserver.isOnline] — VALIDATED internet, not merely an attached interface. */
    val online: Boolean,
    /** [dwAiVerbCapView]'s `spent`, from the mirror. False when this phone has not been told a ceiling. */
    val capSpent: Boolean,
    /**
     * The SERVER's ceiling sentence, when a 429 in this run supplied one. Null otherwise, and the
     * fallback [DW_AI_VERBS_SPENT] is used instead.
     *
     * Not persisted with the numbers, deliberately: `cap_refusal` composes today's breakdown into the
     * sentence, so a stored copy would be a stale statement about a designer's own afternoon, and the
     * zero-cap case is the server's to word and not this client's to guess.
     */
    val capRefusal: String? = null,
)

/**
 * Whether a verb control may be offered at all, and if not, what to say instead.
 *
 * ── A CLOSED TYPE RATHER THAN THE BROWSER'S THREE-VALUED STRING ─────────────────────────────────
 *
 * `verbWorkshopRefusal` in `frontend/lib/aiVerbs.ts` answers `null` / `""` / a sentence, and its own
 * docstring has to warn in bold that **a caller must branch on `!== null` and never on truthiness** —
 * because `""` is falsy in JavaScript and `AiLayersPanel` fed exactly that into a truthiness ternary
 * and rendered live buttons during the IndexedDB read. Kotlin has no truthiness, so the trap does not
 * transfer; what does transfer is the property the trap was protecting, which is that "still reading"
 * is a REFUSAL TO PROCEED that happens to have nothing to say. A closed hierarchy makes that
 * unrepresentable as a sentence and makes forgetting a branch a compile error. **This is a divergence
 * in shape and not in behaviour: the rungs and their order are the browser's, unchanged.**
 */
sealed interface DwVerbGate {
    /** Nothing at the workshop level stands in the way. A caller may go on to its own checks. */
    object Ready : DwVerbGate

    /** The draft is still being read. **Disable the control and draw NOTHING** — see [dwVerbGate]. */
    object StillReading : DwVerbGate

    /** A refusal to render in place of the control. */
    data class Refused(val sentence: String) : DwVerbGate
}

/**
 * The pre-press ladder, as one function, so the order can be asserted rather than read.
 *
 * ── WHY IT IS A FUNCTION AT ALL ─────────────────────────────────────────────────────────────────
 *
 * On the web this ladder was computed inline at three call sites in three nested ternaries, and both
 * properties that matter about it were therefore only checkable by reading all three: that the rungs
 * are in the same ORDER, and that "still reading" is distinguishable from "go ahead". They had come
 * apart twice — the not-on-the-server rung was missing from all three. One function, one order.
 *
 * ── THE ORDER, AND WHY EACH RUNG SITS WHERE IT DOES ─────────────────────────────────────────────
 *
 *  1. **STILL READING.** The floor for [DwVerbConditions.consent] is NOT_RECORDED, so drawing its
 *     sentence before the draft has been read would flash "nobody has been asked" on every workshop
 *     that HAS been asked.
 *  2. **CONSENT.** NOT_RECORDED and REFUSED get different sentences; collapsing them is the defect
 *     `dictation_consent.gate_refusal` keeps two strings apart to avoid.
 *  3. **NOT ON THE SERVER YET — BELOW CONSENT AND NOT ABOVE IT.** It catches the shipped defect
 *     either way, because the defect IS the granted case: consent is recorded on the device and the
 *     workshop screen deliberately supports recording GRANTED on a workshop that has never been up,
 *     so the consent rung passes and every press went out under a local id. But ABOVE consent this
 *     sentence would be shown on workshops where it is not the whole truth: it promises the verbs
 *     *become available* after the next sync, and on a workshop whose recorded answer is REFUSED they
 *     will not. Underneath, all four combinations get a sentence that is unconditionally true.
 *  4. **NO CONNECTION.** Below the two facts a connection cannot change, so a designer in a courtyard
 *     with a REFUSED workshop is told the thing that is actually in their way. Nothing is queued, and
 *     the sentence says so.
 *  5. **THE CEILING.** Last, because it is the only rung this phone may not have been told about —
 *     see [DwAiVerbAllowance] on the missing pre-flight route.
 *
 * A CONTROL IS NEVER HIDDEN, only refused in words. `AiLayersPanel`'s rule 3 — *"a control offered
 * into a certain refusal teaches designers that refusals are noise, after which the one that matters
 * is clicked through too"* — cuts both ways: a control that simply vanishes teaches nothing at all,
 * and every one of these states ends, most of them within one sync.
 */
fun dwVerbGate(conditions: DwVerbConditions): DwVerbGate {
    if (!conditions.draftRead) return DwVerbGate.StillReading
    if (conditions.consent != DwTier3Consent.GRANTED) {
        return DwVerbGate.Refused(dwVerbConsentRefusal(conditions.consent))
    }
    if (!conditions.workshopOnServer) return DwVerbGate.Refused(DW_VERBS_WORKSHOP_NOT_ON_SERVER)
    if (!conditions.online) return DwVerbGate.Refused(DW_VERBS_NEED_A_CONNECTION)
    if (conditions.capSpent) return DwVerbGate.Refused(conditions.capRefusal ?: DW_AI_VERBS_SPENT)
    return DwVerbGate.Ready
}

/**
 * Is there a passage to work on, and is it short enough? Null when the selection is fine.
 *
 * SELECTION-SCOPED AND NOT FIELD-SCOPED, which is the design decision worth defending and is the
 * browser's. Dictation is field-scoped because you speak into a whole field. A verb cannot be: a
 * RICH_TEXT field holds up to 200,000 characters and [DW_VERB_MAX_TEXT_CHARS] is 20,000, so a
 * field-level control would routinely be refused on a stage-13 narrative and a designer would learn
 * that the button is broken. A selection cannot do that.
 *
 * THE LENGTH IS MEASURED ON WHAT WILL BE SENT and the emptiness on the TRIMMED text, which is not an
 * inconsistency: whitespace is not a passage, and whitespace is still characters the body carries.
 */
fun dwVerbPassageRefusal(passage: String): String? {
    if (passage.trim().isEmpty()) return DW_VERBS_NOTHING_SELECTED
    if (passage.length > DW_VERB_MAX_TEXT_CHARS) return dwVerbPassageTooLong(passage.length)
    return null
}

/**
 * A photograph still only on this device has no server id to send, which is its own refusal.
 *
 * [remoteMediaId] is [DraftMedia.remoteMediaId] — written ONLY when `/media/complete` has come back
 * carrying an id, which is that field's whole discipline. Sending this device's own UUID would be a
 * claim about a file the server has never seen, and `_verb_source_media` answers one 404 covering both
 * "not attached to this workshop" and "not yours to read" precisely so that an id on a body cannot be
 * used to find out whether a file exists.
 */
fun dwVerbMediaRefusal(remoteMediaId: String?): String? =
    if (remoteMediaId.isNullOrBlank()) DW_VERBS_MEDIA_NOT_UPLOADED else null

/**
 * The one target language the server refuses, with its reasoning rather than a regex alone.
 *
 * `ai_layers._check_languages` refuses a translation INTO `multi` because a target is a CHOICE the
 * caller makes and not an observation; `multi` remains a perfectly real SOURCE, since these interviews
 * code-switch mid-sentence. Null when the value is acceptable, so a caller can use it directly as the
 * field's error.
 */
fun dwTranslationTargetRefusal(value: String): String? {
    val token = value.trim()
    if (token.isEmpty()) {
        return "Name the language to translate into — a name or a code, such as “Odia”, " +
            "“or” or “English”."
    }
    if (token.equals("multi", ignoreCase = true)) {
        return "“multi” is something a recording can BE, not something a translation can be " +
            "INTO. It is a real answer for the language a passage came FROM — these interviews " +
            "code-switch mid-sentence — but a target is a choice somebody makes. Name the one " +
            "language you want it in."
    }
    return dwVerbLanguageRefusal(token, what = "the target language")
}

/**
 * Is this shaped like a language name at all? `ai_verbs.clean_language`'s refusal, before the round trip.
 *
 * The sentence is the server's own, transliterated, because the two clients and the server must not
 * word one rule three ways. An empty value passes, because omitting the language is always allowed and
 * means "the server records what the run knew".
 */
fun dwVerbLanguageRefusal(raw: String?, what: String): String? {
    val token = raw?.trim().orEmpty()
    if (token.isEmpty()) return null
    if (DW_VERB_LANGUAGE_TOKEN.matches(token)) return null
    return "“${token.take(DW_VERB_MAX_LANGUAGE_CHARS)}” is not a language name this server can use " +
        "for $what. Send the language as a name or a code — “Odia”, “or”, “Hindi”, “multi” — of at " +
        "most $DW_VERB_MAX_LANGUAGE_CHARS characters, with no punctuation."
}

// ---------------------------------------------------------------------------------------------
// The sentences this client authors, because no server ever sends them
// ---------------------------------------------------------------------------------------------

/*
 * THE PRE-PRESS STATES. Written ONCE here and read by every call site, because the alternative is
 * several paraphrases of one rule — and the cross-surface rule for this feature is that the only
 * strings either client authors are these, and they are TRANSLITERATED from `frontend/lib/aiVerbs.ts`
 * rather than reworded. Where a sentence differs from the browser's it is because the browser's names
 * a browser thing (IndexedDB, a page) and the handset's names the handset's; the claim each makes is
 * the same claim.
 */

/** Nothing is selected, so there is nothing to send. */
const val DW_VERBS_NOTHING_SELECTED: String =
    "Select the words you want worked on first. These run over a passage rather than over the whole " +
        "field, so what is selected is what is sent — and is what the layer records as its source."

/** The selection is longer than the server will accept as evidence for one layer. */
fun dwVerbPassageTooLong(chars: Int): String =
    "That selection is $chars characters and at most $DW_VERB_MAX_TEXT_CHARS can be sent. This is a " +
        "bound on the EVIDENCE rather than on the verb: a proofread of the first ten pages of a " +
        "twelve-page note, recorded as a proofread of the note, is a layer whose source is not what " +
        "it says. Select a shorter passage."

/**
 * There is no connection, so the verb genuinely cannot happen — **AND NOTHING HAS BEEN QUEUED.**
 *
 * The second half is the half a designer will otherwise get wrong, and on this device more than in a
 * browser: every other write here banks itself in [OfflineOutbox] and drains on reconnect, and a stage
 * save with no signal is the ordinary case rather than the exception. Silence would therefore invite
 * the reading that the run is waiting to be sent. It is not, and deliberately — see this file's header
 * for the two independent reasons. The last clause exists to stop somebody retyping a passage that is
 * perfectly safe: the words are in the draft in `filesDir` and are untouched.
 */
const val DW_VERBS_NEED_A_CONNECTION: String =
    "These run on the server, so they need a connection. Nothing has been queued for later — a run " +
        "spends real provider credit and counts against today's allowance, so one replayed in three " +
        "days' time would be charged against a day you are not having. Your words are on this phone " +
        "and are untouched; reconnect and select the passage again."

/**
 * The workshop has not been cleared to send anything, so no verb can run.
 *
 * **NOT_RECORDED AND REFUSED GET DIFFERENT SENTENCES**, and collapsing them is the defect
 * `dictation_consent.gate_refusal` keeps two strings apart to avoid: one is answered by asking the
 * artisan, and telling somebody to go and ask again when the answer is already on record is the sort
 * of instruction that teaches a designer to stop reading these messages.
 *
 * It names the fix rather than "ask an administrator", which would be wrong twice over — the server
 * answers 409 and not 403 precisely because *"what is not in a state to permit the send is the
 * WORKSHOP"*, and the designer reading this is the person who can put it right.
 */
fun dwVerbConsentRefusal(consent: DwTier3Consent): String = when (consent) {
    DwTier3Consent.REFUSED ->
        "This workshop's material may not be sent out — that is the answer on record — so nothing " +
            "here can be proofread, written out, translated, described or subtitled. Nothing was " +
            "sent, and your words are exactly as you left them. If the artisan has since agreed, " +
            "change that answer on the workshop's own screen."
    DwTier3Consent.NOT_RECORDED ->
        "Nobody has recorded yet whether material from this workshop may be sent out, so nothing " +
            "here can be proofread, written out, translated, described or subtitled. Open the " +
            "workshop's own screen and record the artisan's answer to that question — until " +
            "somebody does, this stays unavailable."
    // GRANTED never reaches here through [dwVerbGate], which tests for it first. It is spelled out
    // rather than left to an `else` so that a fourth consent state added to the enum fails to compile
    // here instead of silently inheriting the "nobody has been asked" sentence — which is the one
    // reading that would be false about every workshop.
    DwTier3Consent.GRANTED ->
        "This workshop's material may be sent out; there is nothing standing in the way of these."
}

/** A photograph the server has never seen cannot be described by a model running on the server. */
const val DW_VERBS_MEDIA_NOT_UPLOADED: String =
    "This file has not reached the server yet, so there is nothing to send — the verb runs on the " +
        "server's copy. It goes up with the next sync, and you can describe it then."

/**
 * **THE WORKSHOP ITSELF HAS NEVER REACHED THE SERVER**, so no verb has a record to run over.
 *
 * ── THE DEFECT, WHICH SHIPPED ON THE OTHER CLIENT ───────────────────────────────────────────────
 *
 * Every verb route is `/design-workshops/{id}/…` and `load_workshop_or_404` finds no row for a
 * device-only id, so each press answered a bare 404 — "Record not found", a sentence written about a
 * missing record rather than about an unsent workshop, naming no next move. The consent rung actively
 * HIDES it, because a consent recorded in a courtyard is GRANTED on a workshop that has never been up.
 *
 * This is the same door [DwDictationConditions.workshopOnServer] closed for rung 2 of the dictation
 * ladder, with a comment naming exactly how it was opened: *"a local id is a perfectly good string"*.
 * The verbs would have walked through it a lane later.
 */
const val DW_VERBS_WORKSHOP_NOT_ON_SERVER: String =
    "This workshop is still only on this phone, so there is nothing for a model to read — these run " +
        "on the server's copy and the server has never seen this one. It goes up with the next sync " +
        "and they become available then. Nothing you have written is at risk: it is in the draft " +
        "here, and no run has been queued, because a run spends provider credit against the day it " +
        "is made."

/**
 * Today's ceiling in this client's words — **the fallback, used only when the server supplied none.**
 *
 * Reached when the numbers say spent but no 429 sentence is in hand: after a 201 that left
 * `aiVerbsRemaining` at zero, or from the mirror on a later press. It deliberately does not invent the
 * ZERO-CAP case, which is the server's to word (`cap_refusal` has a separate sentence for a deployment
 * that has switched these verbs off, because "you have used all 0" reads as a bug to somebody who has
 * run nothing all morning).
 */
const val DW_AI_VERBS_SPENT: String =
    "Today's runs of the writing and captioning models are used up on this account. Dictation has " +
        "its own separate allowance and is unaffected — and everything you have written is untouched."

/**
 * Said before the press on the one verb that costs an upload of bytes the archive already holds.
 *
 * The route's own docstring calls this *"a defect rather than a design"*: ElevenLabs Scribe v2 is
 * already asked for word timings and Deepgram Nova-3 already returns sentence and word timings, and
 * both are discarded one line after being parsed. So nothing already transcribed can be subtitled
 * without sending the audio again. Worth saying on a handset more than in a browser: this is mobile
 * data out of a designer's own bundle, per recording.
 */
const val DW_SUBTITLES_SECOND_UPLOAD_NOTE: String =
    "Subtitling sends this recording to a transcription engine again. The timings are the whole " +
        "point of it and nothing already stored has them, so even a recording this workshop has " +
        "already transcribed has to go up a second time — which costs an upload, your mobile data, " +
        "and one run of today's allowance."

/**
 * **THE ONE VERB THAT NEVER RUNS ON A DESIGNER'S OWN KEY**, said out loud because no client can tell.
 *
 * Four of the five verbs pass `user_id=current_user.id` into the key resolver, which hands back the
 * designer's own key when they have one that can do the task and falls back to the deployment's
 * otherwise — so bring-your-own-key is invisible and needs no branch. `subtitle_ai_layer` is
 * different: it calls `ai.transcribe_timed_bytes(content, filename, mime, get_settings())`, and that
 * function's signature HAS NO `user_id` PARAMETER AT ALL. A backend asymmetry rather than a client
 * one, and nothing here changes it — but a designer who supplied a key expecting to pay for their own
 * work is silently on the organisation's bill for this verb alone.
 */
const val DW_SUBTITLES_DEPLOYMENT_KEY_NOTE: String =
    "Subtitles always run on this server's own transcription key, even if you have supplied one of " +
        "your own — the other four verbs use yours when you have one. That is a limitation of the " +
        "server rather than a choice made here, and it means the cost of this run falls on the " +
        "organisation."

// ---------------------------------------------------------------------------------------------
// Reading a stored cue list
// ---------------------------------------------------------------------------------------------

/**
 * The two subtitle files, and what each is actually FOR — so a designer picks by the player rather
 * than by the extension. Neither is a superset of the other.
 *
 * [mimeType] is the bare type without the server's `; charset=utf-8` parameter, because it is handed
 * to `MediaStore.Downloads.MIME_TYPE`, which is a type and not a Content-Type header.
 */
enum class DwSubtitleFormat(val extension: String, val mimeType: String, val label: String) {
    /** What a phone gallery, VLC and every desktop player open, and what a designer attaches to an email. */
    SRT("srt", "application/x-subrip", "SubRip (.srt) — the phone gallery, VLC, an email attachment"),

    /** What a browser's `<track>` element takes. */
    VTT("vtt", "text/vtt", "WebVTT (.vtt) — a browser video player"),
}

/** One cue, as `subtitles.Cue.payload` writes it. `speaker` and `estimated` are absent when false. */
data class DwSubtitleCue(
    val start: Double,
    val end: Double,
    val text: String,
    /** **THE ENGINE'S GUESS** at who was speaking, present only when it made one. Never a person's name. */
    val speaker: String? = null,
    /** True when this cue's boundary was INTERPOLATED rather than reported by the provider. */
    val estimated: Boolean = false,
)

/** A stored SUBTITLES payload as a screen reads it — `subtitles.cues_payload`, wrapper included. */
data class DwSubtitleSummary(
    /** Cues in the list. Read from the stored `count` where there is one — see [dwSubtitleCueSummary]. */
    val count: Int,
    /**
     * How many boundaries were INVENTED rather than reported. Usually zero, and zero is a measured
     * statement rather than a default.
     *
     * **IT IS NOT RETROACTIVE**, and `cues_payload` says so: a cue list stored before this key existed
     * carries no `estimated` markers and reads as zero whether or not its boundaries were invented.
     * Nothing on either side can tell the two apart, so a surface must print it as a count and never
     * as a guarantee that the rest are exact.
     */
    val estimatedCues: Int,
    /** The longest end time in seconds, or null when the payload did not carry one. */
    val durationSeconds: Double?,
    /** The language the cues are in, as the run reported it. Null is "nobody detected one", not English. */
    val language: String?,
    /**
     * Whether ANY cue carries a speaker label — and therefore whether `?speakers=` may be offered.
     * Asking for labels that do not exist is a 422 by design: *"These subtitles carry no speaker
     * labels, so a file with them in would be the same file without."*
     */
    val hasSpeakers: Boolean,
    val cues: List<DwSubtitleCue>,
    /** False when this payload is not a cue list at all — a client must say so rather than show nothing. */
    val readable: Boolean,
)

/** `subtitles.PAYLOAD_SCHEMA`. */
private const val DW_SUBTITLE_PAYLOAD_SCHEMA = "dw.subtitles/1"

private val DW_SUBTITLE_SUMMARY_EMPTY = DwSubtitleSummary(
    count = 0,
    estimatedCues = 0,
    durationSeconds = null,
    language = null,
    hasSpeakers = false,
    cues = emptyList(),
    readable = false,
)

/**
 * A stored SUBTITLES payload, as a screen reads it.
 *
 * **TOLERATES THE BARE LIST AS WELL AS THE WRAPPED OBJECT**, exactly as `subtitles.cues_of_payload`
 * does and for its stated reason: a payload written by an on-device Tier 1 or Tier 2 runner may not
 * have gone through `cues_payload`, and the tiers are allowed to differ in how they produce a thing
 * and are not allowed to differ in what it means.
 *
 * `count` and `estimatedCues` are read from the WRAPPER where there is one rather than recomputed: the
 * server stores them so a list can say "142 cues, 11 of them approximate" without carrying every cue,
 * and a client that recomputed would silently disagree with the annexure the moment a payload was
 * truncated anywhere.
 *
 * A cue whose `start` or `end` is not a number is DROPPED rather than defaulted to zero. A cue at
 * 00:00:00 that belongs at 41 minutes is worse than an absent one: it lands over the opening frame of
 * a video somebody is showing to a ministry officer.
 */
fun dwSubtitleCueSummary(payload: JsonElement?): DwSubtitleSummary {
    if (payload == null) return DW_SUBTITLE_SUMMARY_EMPTY
    val wrapper = payload as? JsonObject
    val raw = (payload as? JsonArray) ?: (wrapper?.get("cues") as? JsonArray)
        ?: return DW_SUBTITLE_SUMMARY_EMPTY

    val cues = raw.mapNotNull { entry ->
        val cue = entry as? JsonObject ?: return@mapNotNull null
        val start = (cue["start"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val end = (cue["end"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (start == null || end == null) return@mapNotNull null
        DwSubtitleCue(
            start = start,
            end = end,
            text = (cue["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            speaker = (cue["speaker"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() },
            estimated = (cue["estimated"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun wrapped(key: String): String? = (wrapper?.get(key) as? JsonPrimitive)?.contentOrNull
    return DwSubtitleSummary(
        count = wrapped("count")?.toIntOrNull() ?: cues.size,
        estimatedCues = wrapped("estimatedCues")?.toIntOrNull() ?: cues.count { it.estimated },
        durationSeconds = wrapped("durationSeconds")?.toDoubleOrNull(),
        language = wrapped("language")?.trim()?.takeIf { it.isNotEmpty() },
        hasSpeakers = cues.any { it.speaker != null },
        cues = cues,
        // A wrapper naming a schema this build does not know is still read for its cues — the shape
        // has one version and a newer one would still be a cue list — but the flag records that this
        // was a recognisable payload at all, which is what a screen needs to say "there is nothing to
        // show" rather than "this is broken".
        readable = cues.isNotEmpty() || wrapped("schema") == DW_SUBTITLE_PAYLOAD_SCHEMA,
    )
}

/**
 * `3849.48` -> a caption reader's `1:04:09`, or `4:09` under an hour. Seconds are TRUNCATED.
 *
 * Truncated and never rounded up, because a cue label that reads one second later than the frame it
 * belongs to sends somebody scrubbing past the line they were looking for.
 */
fun dwSubtitleTimecode(seconds: Double): String {
    val whole = maxOf(0L, seconds.toLong())
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    val mm = if (hours > 0L) minutes.toString().padStart(2, '0') else minutes.toString()
    val hh = if (hours > 0L) "$hours:" else ""
    return "$hh$mm:${secs.toString().padStart(2, '0')}"
}
