package com.designprototype.workshop.data

/**
 * **WHAT A TIER 2 MODEL WOULD BE ALLOWED TO PRODUCE, AND THE SHAPE THE PHONE WOULD HAVE TO SEND. THE
 * LAYERING LAW, WRITTEN DOWN ON THE DEVICE SIDE BEFORE ANY DEVICE CAN RUN A MODEL.**
 *
 * Nothing here runs a model — [DW_TIER2_RUNTIME_ABSENCE] says why in one sentence — and nothing here
 * makes a network call. What it is: the payload a handset-produced layer would be posted as, the
 * provenance that is mandatory in it, and the two gates that must NOT be in front of it. Written now,
 * with tests, because the order the brief gives is deliberate: the runtime is a Kotlin upgrade away and
 * the write path is a decision nobody has taken, and of the two the decision is the one that gets made
 * badly under time pressure.
 *
 * ── THE FIVE RULES, AND WHERE EACH IS ENFORCED IN THIS FILE ────────────────────────────────────
 *
 *  1. **EVERY OUTPUT IS A ROW AND NEVER AN EDIT.** [dwTier2LayerBody] produces a body for
 *     `POST /api/design-workshops/{id}/ai-layers…`, which appends. There is no function here that
 *     takes a field key, and [DwStageEntry] is not imported — see rule 5.
 *  2. **PROVENANCE IS MANDATORY.** [DwTier2Provenance] has no defaults and refuses a blank in every
 *     field; a run that cannot say which model produced it cannot be expressed.
 *  3. **IT IS INERT UNTIL A PERSON ACCEPTS IT.** The body carries no `accepted` field and no
 *     `acceptedAt` — acceptance is a separate authenticated act
 *     (`POST /{workshop_id}/ai-layers/{layer_id}/accept`), and a device that could post an
 *     already-accepted row would be a device that can put model prose into a report unattended.
 *  4. **NEITHER MONEY GATE IS WIRED.** No dictation consent, no daily provider cap. See
 *     [DW_TIER2_NO_GATES_NOTE] for the argument and `DwTier2LayerTest` for the assertion that this
 *     file mentions neither.
 *  5. **NO PATH NAMES A STAGE ENTRY AS WRITABLE.** A layer is a row beside the designer's words, never
 *     over them. The test reads this file's own source and fails if `DwStageEntry` appears in it.
 *
 * ── THE ONE THING THAT IS STILL UNDECIDED, AND IT IS NOT DECIDED HERE ─────────────────────────
 *
 * **THERE IS NO ROUTE ON THE SERVER THAT ACCEPTS A LAYER A PHONE PRODUCED.** Read rather than assumed:
 * `backend/app/api/routes/design_workshops.py` sets `_SERVER_TIER = ai_layers.AiTier.TIER_3` as a
 * module constant and passes it to every one of the five verb routes; `AiLayerRegisterIn` has no `text`
 * field **on purpose** — its own docstring says a text field "would turn this endpoint into a way for
 * any client to post model prose into a workshop record under a provenance of its own choosing".
 *
 * That reasoning does not evaporate when the model moves onto the handset: the server still cannot tell
 * a phone that ran Gemma from a browser claiming to have. What changes is that the alternative — never
 * accepting device output — makes Tier 2 pointless. **The decision that has to be taken by whoever owns
 * the API, and is deliberately not taken in this file, is how a device-produced layer's tier is
 * established.** The shape this file is built for, because it is the one that keeps the existing
 * discipline intact, is: **the route fixes the tier, exactly as today's routes fix TIER_3** — so
 * [dwTier2LayerBody] does NOT send a tier at all. What that leaves open is what makes the route
 * device-only, and this app has no device identity to offer today. Naming that gap is this file's
 * contribution to the decision; closing it is not.
 */

// ---------------------------------------------------------------------------------------------
// Which verbs are real on a handset
// ---------------------------------------------------------------------------------------------

/**
 * The verbs a Tier 2 model on this phone could actually produce. **Four, and the fifth is absent for a
 * reason that is not caution.**
 *
 * Each value exists because something about the artifact or the runtime makes it reachable, checked
 * against the LiteRT-LM API surface (`javap` on `litertlm-android-0.16.0.aar`) rather than against a
 * marketing page. The server-side vocabulary these map onto is `ai_layers.LayerKind`, and the names are
 * kept identical so a reader comparing the two files does not have to hold a translation table.
 */
enum class DwTier2Verb {
    /**
     * Spelling, grammar and punctuation, changing nothing else. **The one to ship first.**
     *
     * Text in, text out: the plainest thing a decoder can be asked for, and the verb whose failure
     * mode is visible to the person who wrote the words. Of the server-side planners `ai_verbs`' own
     * module docstring says *"The planners would serve it unchanged"* — "it" being an on-device run —
     * because every one of them already takes the tier as an argument with no default.
     */
    PROOFREAD,

    /**
     * A terse field note written out into prose. **Reachable, and the last one that should be wired.**
     *
     * `ai_verbs.expand` calls itself "the most dangerous verb in this system" — it is the only kind in
     * the vocabulary that invents sentences rather than transforming ones somebody said. Being able to
     * run it on a phone does not make it the one to run first.
     */
    EXPANDED,

    /**
     * A sibling translation, never a replacement for the original.
     *
     * Reachable, with a caveat that must survive into any row a designer reads: which languages this
     * artifact can actually write is **unmeasured** — see [DwModelPlan.languages] and the
     * `unmeasuredLanguagesNote` on both Tier 2 rows. Google's "35+ out of the box" is a claim.
     */
    TRANSLATION,

    /**
     * A caption for a photograph. **The finding that changes the design, and it was proved by reading
     * the container rather than inferred.**
     *
     * The vision encoder is physically inside the main `.litertlm` file: the section list read out of
     * `gemma-4-E2B-it.litertlm` includes `tf_lite_vision_encoder` and `tf_lite_vision_adapter`, while
     * both `-gpu` variants contain exactly one section, `tf_lite_artisan_text_decoder`, and are
     * therefore text-only. The runtime exposes it — `EngineConfig(…, visionBackend, …)`,
     * `Content.ImageFile`, `InputData.Image` — and Google's chart puts the vision encoder at about
     * 205 MiB loaded on demand over the text-only footprint. `MEDIA_ROOTED_KINDS` on the server
     * already admits CAPTION with the photograph as its evidence rung, so no schema changes.
     */
    CAPTION,
    ;

    /*
     * SUBTITLES IS NOT IN THIS ENUM, AND ITS ABSENCE IS A MEASUREMENT.
     *
     * `ai_verbs.subtitle` needs timed fragments and `fit_cues` needs them to place a cue; nothing in
     * the LiteRT-LM API returns a timing for anything. Gemma 4 E2B/E4B do speech-to-text and even
     * speech-to-translated-text, which is a reason to keep them out of the transcription lane's way
     * rather than a reason to use them there — a transcript with no timings cannot become a subtitle,
     * and inventing timings for one is the fabrication this repository has a document about.
     */
}

// ---------------------------------------------------------------------------------------------
// The provenance, which has no defaults
// ---------------------------------------------------------------------------------------------

/**
 * **WHICH MACHINE RAN A VERB, WHAT RAN ON IT, WHEN, AND IN WHAT LANGUAGE. NO DEFAULTS, NO NULLS THAT
 * READ AS "none".**
 *
 * The mirror of `ai_verbs.VerbRun` on the device side, and it keeps that class's one hard rule: a
 * caller with nothing to say has to write [DW_TIER2_UNRECORDED] deliberately — visible in a diff and in
 * the row — rather than omitting an argument and getting a null that reads like an answer.
 *
 * **[tier] IS NOT A FIELD.** It is a constant of this file, [DW_TIER2_TIER], because the tier of an
 * on-device run is the one thing about it that is not in question, and a settable tier is how a
 * provenance column stops being worth reading. It is also not sent — see [dwTier2LayerBody].
 */
data class DwTier2Provenance(
    /** Who ran it. For a handset run this is the app itself, not a paid provider. */
    val provider: String,
    /** The exact artifact, matching a [DW_TIER2_PLANS] row's [DwModelPlan.modelId]. */
    val modelId: String,
    /** The build of the runtime that loaded it — the AAR version, not the model's. */
    val modelVersion: String,
    /**
     * The language of the OUTPUT, as a BCP-47 tag or the word `multi`.
     *
     * `multi` is a real answer and not a placeholder, the same judgement `AiLayerRegisterIn.language`
     * records for Deepgram Nova-3: a workshop is Hindi code-switched with English mid-sentence.
     */
    val language: String,
    /** When the MODEL ran, ISO-8601 with an offset. Never "now" filled in by whoever is writing a row. */
    val producedAtIso: String,
) {
    init {
        require(provider.isNotBlank()) {
            "A run has to say what produced it. Write DW_TIER2_UNRECORDED if it genuinely is not " +
                "known; a blank would print as an empty provenance column and read as “no model”."
        }
        require(modelId.isNotBlank()) {
            "A run has to name the exact artifact. “Gemma” is a family; a provenance column carrying " +
                "a family name cannot tell a reviewer which of two models with different footprints " +
                "and different licences wrote the words they are reading."
        }
        require(modelVersion.isNotBlank()) {
            "A run has to name the runtime build that loaded the model. A defect found in one " +
                "version of LiteRT-LM is traceable only if the row says which one ran."
        }
        require(language.isNotBlank()) {
            "A run has to say what language it produced, or the word “multi”. This is the field a " +
                "reader uses to know whether the prose beside an artisan's Odia is a translation."
        }
        require(producedAtIso.isNotBlank()) {
            "A run has to say WHEN the model ran. The server records when the row appeared; that is " +
                "a different moment, and on a handset that has been offline for a day it is a very " +
                "different one."
        }
    }
}

/** The word a provenance field carries when nobody recorded it. Matches `ai_layers.UNRECORDED`. */
const val DW_TIER2_UNRECORDED: String = "UNRECORDED"

/**
 * The tier an on-device run is, in the server's own vocabulary. **A constant, never an argument.**
 *
 * `DwAiTier.TIER_2` exists one file over and is the same fact; this is the string form that would go on
 * the wire, kept beside the payload builder so that the two cannot come to differ. It is deliberately
 * NOT put in the body — see [dwTier2LayerBody].
 */
const val DW_TIER2_TIER: String = "TIER_2"

/** Where the words a verb worked on came from. **One of these, never two, and never a stage field.** */
sealed interface DwTier2Source {
    /** An existing layer's prose — a transcript rung, a cleaned rung. Chained, as the law requires. */
    data class Layer(val layerId: String) : DwTier2Source

    /** A photograph or recording already attached to this workshop. The evidence rung for CAPTION. */
    data class Media(val mediaId: String) : DwTier2Source

    /**
     * Words the designer typed and handed to the model in this run, kept verbatim on the row.
     *
     * The reason it is a source at all is EXPANDED, which has nothing upstream to point at. It is the
     * source that must never be confused with a stage entry: the designer's field note stays exactly
     * where they typed it, and what travels is a COPY that this row records so a reviewer can see what
     * the model was given.
     *
     * **IT GOES ON THE WIRE AS `sourceText`, WHICH IS THE COLUMN'S OWN NAME.** An earlier draft of
     * this file sent `suppliedText` and said "the server calls this `suppliedText`". Checked: the
     * string `suppliedText` occurs **nowhere** in `backend/` — not in a schema, not in a column, not
     * in a route. What the server has is an internal factory, `ai_layers.LayerSource.supplied_text()`,
     * and a Postgres column, `DwAiLayer.sourceText`, which is one of the three columns the
     * `DwAiLayer_source_is_exactly_one` CHECK counts. A wire key is named after the column it lands
     * in, so that a reader of the migration and a reader of this file are looking at one name.
     */
    data class SuppliedText(val text: String) : DwTier2Source
}

/**
 * One thing a handset-run verb produced, ready to be posted as a row. **Not accepted, not applied.**
 *
 * A draft rather than a layer, in the name, because that is the whole of its status: it exists on the
 * phone, it will become a row when it is posted, and it is inert in a report until a person accepts it.
 * Nothing in this type can express "already accepted".
 */
data class DwTier2Draft(
    val verb: DwTier2Verb,
    val source: DwTier2Source,
    /** What the model wrote. Verbatim: this app does not tidy model output before recording it. */
    val text: String,
    val provenance: DwTier2Provenance,
) {
    init {
        require(text.isNotBlank()) {
            "A layer with no words in it is not a layer. A verb that produced nothing should record " +
                "a failure where failures are recorded (DwLoadFailureNote), not an empty row that a " +
                "reviewer has to open to discover is empty."
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The body, and what is deliberately not in it
// ---------------------------------------------------------------------------------------------

/**
 * The JSON body a device-produced layer would be posted as. **Six keys, and the omissions are the
 * design.**
 *
 * A `Map` rather than a serializable class because there is no route to send it to yet, and a
 * `@Serializable` DTO named after an endpoint that does not exist would be the first thing somebody
 * wired to the wrong one. When the route lands, this map is the contract it has to satisfy and the
 * test below is what pins it.
 *
 * **WHAT IS NOT IN IT, AND WHY EACH ABSENCE IS LOAD-BEARING:**
 *
 *  * **no `tier`** — the route fixes it, exactly as the five existing verb routes fix `TIER_3` from a
 *    module constant. A body that could state its own tier makes the tier column worthless, which is
 *    `AiLayerRegisterIn`'s own argument for having no `text` field.
 *  * **no `accepted` / `acceptedAt`** — acceptance is a separate act by a person, and a device that
 *    could post an accepted row could put model prose in a report with nobody reading it.
 *  * **no field key, no stage key, no `entryId`** — there is no shape here that addresses a designer's
 *    own writing. A layer sits beside it.
 *  * **no consent token and no cap counter** — see [DW_TIER2_NO_GATES_NOTE].
 *
 * ── ONE HAZARD IN THIS MAP, NAMED BECAUSE IT IS NOT FIXABLE FROM THIS SIDE ────────────────────
 *
 * **`text` MEANS THE OPPOSITE THING ON EVERY ROUTE THAT EXISTS TODAY, AND THE ROUTE THIS BODY IS
 * WAITING FOR WOULD BE WRITTEN NEXT TO THOSE ONES.** Here `text` is what the model WROTE. In
 * `AiProofreadIn`, `AiExpandIn` and `AiTranslateIn` — the five bodies a device route would be a sibling
 * of, under the same `/ai-layers/…` prefix — `text` is the passage the verb is to be run OVER. The two
 * fields are the same type, the same shape, and one character apart in a handler.
 *
 * The failure that makes this worth a paragraph is not a wrong column. It is: a device posts its
 * finished Gemma output as `text`; a route modelled on `AiProofreadIn` reads `text` as the passage to
 * proofread; the server runs its own Tier 3 chain over model prose, records the result as
 * `_SERVER_TIER = TIER_3`, and **charges `ai_verb_cap` for it** — a paid call made by a feature whose
 * whole claim is that it spends nothing. Whoever writes the route must therefore decide the name
 * deliberately; `DwAiLayer.text` is the column this lands in and is the reason it is spelled that way
 * here, but the column being right does not make the request unambiguous.
 *
 * It is left as `text` rather than quietly renamed because inventing a third vocabulary for prose — a
 * name matching neither the column nor the sibling routes — would make the collision harder to see
 * rather than safer. `DwTier2LayerTest` pins both keys so a rename is a decision and not a drift.
 */
fun dwTier2LayerBody(draft: DwTier2Draft): Map<String, String> {
    val body = linkedMapOf(
        "kind" to draft.verb.name,
        "text" to draft.text,
        "provider" to draft.provenance.provider,
        "modelId" to draft.provenance.modelId,
        "modelVersion" to draft.provenance.modelVersion,
        "language" to draft.provenance.language,
        "producedAt" to draft.provenance.producedAtIso,
    )
    when (val source = draft.source) {
        is DwTier2Source.Layer -> body["sourceLayerId"] = source.layerId
        is DwTier2Source.Media -> body["sourceMediaId"] = source.mediaId
        is DwTier2Source.SuppliedText -> body["sourceText"] = source.text
    }
    return body
}

/**
 * **WHY AN ON-DEVICE VERB IS BEHIND NEITHER MONEY GATE. THE ARGUMENT, SO NOBODY ADDS ONE BY HABIT.**
 *
 * *Dictation consent* (`backend/app/services/dictation_consent.py`) exists because Tier 3 dictation
 * sends a recording of an artisan's voice off the handset to a provider. **A model running on the phone
 * sends nothing anywhere**, so requiring consent for it would ask an artisan to agree to a disclosure
 * that does not happen — and would train everyone to tap through the consent that does matter.
 *
 * *The daily cap* (`services/dictation_cap.py`, `services/ai_verb_cap.py`) is a ceiling on money spent
 * at a provider. A local run spends none. The scope is not an inference from that, either — it is an
 * explicit instruction from the repository owner: the cap *"should only apply to the global /dictate
 * when it is utilizing the ElevenLabs / Deepgram / Whisper API, and not … the one through sherpa-onnx
 * or from the local SLM"*. `backend/app/api/routes/asr_models.py` already states the same rule for the
 * artifact download and has a test that it imports neither module; this note is that rule for the verb.
 */
const val DW_TIER2_NO_GATES_NOTE: String =
    "A model that runs on this phone sends nothing off it, so there is nothing to consent to and " +
        "nothing to pay for. Consent and the daily limit are about recordings leaving the phone and " +
        "money spent at a provider; neither applies here, and neither is asked."

/** Why a draft cannot be posted yet. **Every value is a thing that does not exist, not a refusal.** */
enum class DwTier2WriteBlocker {
    /** No runtime in this build, so nothing could have produced a draft in the first place. */
    NO_RUNTIME_IN_THIS_BUILD,

    /**
     * **THE ONE THAT IS NOT ABOUT THE PHONE.** The server has no route that accepts a layer a device
     * produced: every verb route fixes `TIER_3`, and the registration body refuses a `text` field.
     */
    NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER,
}

/**
 * Whether a draft could be posted, and if not, which missing thing stops it. **Null is never returned
 * today**, and the two blockers are ordered as the checks would run: without a runtime there is no
 * draft, and with one there is still nowhere to send it.
 *
 * Both parameters default to the real state of the world so that a caller writing production code gets
 * today's answer, and a test can pass the world it wants to pin — the same shape `dwTier1Offer` uses
 * for `runtimeInApk`.
 */
fun dwTier2WriteBlocker(
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
    routeExists: Boolean = DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS,
): DwTier2WriteBlocker? = when {
    !runtimePresent -> DwTier2WriteBlocker.NO_RUNTIME_IN_THIS_BUILD
    !routeExists -> DwTier2WriteBlocker.NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER
    else -> null
}

/**
 * Whether the server has a route that accepts a layer produced on a device. **It does not.**
 *
 * A constant rather than a probe because it is a property of the deployment's code and not of the
 * network: asking a server at runtime whether it has the route would turn a design decision nobody has
 * taken into a 404 a designer sees.
 */
const val DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS: Boolean = false

/** One sentence for a surface that has to explain why a verb it could run has nowhere to put a result. */
const val DW_TIER2_NO_WRITE_PATH_SENTENCE: String =
    "Even with a model on the phone there is nowhere yet to record what it produced: this app's " +
        "server accepts model output only from its own cloud chain. That is one decision away, and it " +
        "is a decision about how a phone proves what produced a row — not a limitation of the phone."
