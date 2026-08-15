package com.designprototype.workshop.data

/**
 * WHICH ENGINE WRITES DOWN ONE DICTATED SENTENCE, IN WHAT ORDER THEY ARE ASKED, AND WHAT IS SAID
 * WHEN THEY ARE ALL EXHAUSTED.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * The web has two rungs and this handset had one. `frontend/components/designworkshop/Dictation.tsx`
 * uses the browser's own `SpeechRecognition` where there is one, and where there is not it records a
 * clip and posts it to `POST /design-workshops/{id}/dictate`, which runs it through
 * `ai.transcribe_audio_bytes` — the provider chain that boosts a craft keyterm list
 * (`backend/app/services/ai.py:118-138`) precisely because a general model writes **dabu**, a
 * mud-resist printing technique, as **double**.
 *
 * Android had no second rung. It was `SpeechRecognizer`, and on a failure it fell back to GOOGLE'S
 * GENERIC NETWORK RECOGNISER, which has none of that vocabulary.
 *
 * MEASURED (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md, the fleet's Galaxy M32 answering for
 * itself): Google's on-device catalogue on that handset holds thirty languages and exactly two of
 * our nineteen — `hi-IN` and `en-IN`. For the other seventeen, INCLUDING ODIA, THE LANGUAGE OF THE
 * STATE THESE WORKSHOPS RUN IN, every dictation went to the generic network engine. So on the same
 * audio, in the same application, the web produced a craft-aware transcript and the phone produced
 * "double" where the artisan said "dabu" — the exact cross-surface divergence this repository has
 * spent its history removing. See docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md §1.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Everything below is plain Kotlin over plain data: no Context, no `android.speech`, no Compose, no
 * okhttp. That is what lets the desktop JVM test it (`DwDictationLadderTest`), and this repository's
 * catalogue of shipped defects says the untestable half is the half that is wrong. The recorder and
 * the upload live in [DwDictationUpload]; the control that walks this plan lives in
 * `ui/designworkshop/DwDictation.kt`. The same split as `DwLanguagePacks.kt`, for the same reason.
 *
 * ── THE LADDER, AND THE ONE ORDERING THAT MATTERS ─────────────────────────────────────────────
 *
 * ```
 * 1. Installed on-device pack   free, instant, streaming, works with no signal.   KEPT FIRST.
 * 1b. THIS APP'S OWN MODEL      free, offline, and it serves LANGUAGES ANDROID DOES NOT. ADDED.
 * 2. Server dictation (online)  the web's own path: craft keyterms, language=multi. ADDED.
 * 3. Google network recogniser  last resort only.                                  DEMOTED.
 * 4. Honest failure             "type it in", which was said far too early.
 * ```
 *
 * Rung 1 stays first on purpose: it is free, instant and offline, and spending provider credit per
 * sentence when the phone can already do the job would be a bill for nothing. The change is that
 * **where rung 1 cannot serve the language at all — the seventeen — rung 2 now comes BEFORE Google's
 * network engine** instead of after it.
 *
 * ── AND RUNG 1 IS NOW TWO RUNGS, BECAUSE "ON THE PHONE" IS TWO DIFFERENT ENGINES ───────────────
 *
 * Once this app carries a speech model of its own (`DwAsrRuntime.kt` for the engine,
 * `DwModelChoice.kt` for which model, `DwModelLanguages.kt` for what it hears) there are **two**
 * offline possibilities on one handset, and **they do not serve the same languages.** That is the
 * whole reason the second rung exists rather than the first one being widened: Google's on-device
 * catalogue covers two of our nineteen on the fleet's own M32 (measured), and the models this app
 * would install are chosen precisely for some of the other seventeen.
 *
 * [DwDictationRung.APP_SPEECH_MODEL] therefore sits **immediately below Android's pack and above
 * everything that costs money or needs signal**, and each half of that placement is an argument:
 *
 *  * **BELOW Android's pack**, because where the platform has the language its pack streams partial
 *    results as the designer speaks and has been measured to exist; ours is a model whose accuracy
 *    in a courtyard is, in plan §2.2's own terms, unmeasured. Where both can serve a language, the
 *    one that is already working keeps the job.
 *  * **ABOVE both online rungs, including on the handsets that cannot be asked about packs.** It is
 *    free, it needs no signal, and it is on the phone already because somebody chose to install it.
 *    Putting anything that spends provider credit or waits on a bar of signal in front of it would
 *    make the download this app just asked a designer to pay for do nothing most of the time.
 *
 * **IT NEVER TAKES THE SERVER RUNG AWAY FROM A LANGUAGE IT CANNOT HEAR.** The rung is offered for
 * exactly the languages the installed model was MEASURED to serve
 * ([DwDictationConditions.appModelServesLanguage], computed by `dwAppModelCoverage`), so a language
 * with no offline coverage keeps precisely the ladder it has today — and a model that hears none of
 * the nineteen changes nothing anywhere. A test pins that every existing plan is unchanged when no
 * app model is installed, which is every handset in the fleet today.
 *
 * ── THE COST, SAID OUT LOUD ───────────────────────────────────────────────────────────────────
 *
 * Every rung-2 dictation spends provider credit, and on a handset with none of these packs that is
 * every dictated sentence of a fortnight's fieldwork. This file makes sure the credit is never spent
 * while the phone itself could have done the job — and, since plan §6 was answered on 2026-08-11, it
 * makes sure it is not spent at all until two further conditions hold.
 *
 * ── THE TWO THINGS THAT NOW GATE RUNG 2, AND THE THIRD THE ROUTE ITSELF ASKS FOR ──────────────
 *
 * Plan §6 answers 3 and 1, in the user's own words:
 *
 *  * CONSENT, PER WORKSHOP, RECORDED. "A workshop carries an explicit answer to 'may recordings and
 *    dictation from this workshop leave the device for a third-party provider', with who set it and
 *    when. Until it is answered yes, rung 2 is unavailable and says why." An account-level setting
 *    was rejected because a consent given for one cluster would silently cover the next one, and the
 *    artisan whose voice it is changes between them.
 *  * A PER-DESIGNER DAILY CAP, "named in words when it is hit". Authoritative on the server; what
 *    reaches [DwDictationConditions] is a mirror of it, and the mirror exists so the phone can refuse
 *    BEFORE spending a six-megabyte upload on the connection — which in a district town is the
 *    scarce resource, more so than the provider credit the cap is about.
 *
 * BOTH GATE RUNG 2 AND NEITHER GATES RUNG 1. A phone with an installed on-device pack goes on
 * dictating in a courtyard, for free, with no consent question asked of anybody — BECAUSE NOTHING
 * LEAVES THE DEVICE. [dwDictationLadder]'s `deviceRung` reads neither field and must not be made to.
 *
 * AND A THIRD FACT NOW GATES IT TOO, which is not a policy but the shape of the route:
 * [DwDictationConditions.workshopOnServer]. Rung 2 posts to `POST /design-workshops/{id}/dictate`,
 * THE ONLY dictation route that can read a workshop's `dictationConsent` column at all — the id-less
 * `POST /design-workshops/dictate` consults nothing and hands the clip straight to the provider chain,
 * which is why this app no longer posts to it. A clip therefore needs a workshop id the server can
 * load, and where there is none there is no consent behind the clip and nothing to check it against,
 * so rung 2 is withheld with a sentence like the other two. THE COST IS REAL AND IS NOT HIDDEN: a
 * workshop started in a courtyard and not yet sent up loses the craft-aware rung until it is sent,
 * even with the artisan's answer recorded on this phone. The alternative was going on posting through
 * the door with no gate on it, which is the defect this closed.
 *
 * ── AND WHAT IT COSTS WHEN THE GATE CLOSES, WHICH IS NOT DISCOVERABLE FROM THE GATE ────────────
 *
 * On the fleet's Galaxy M32 asking for Odia — NO_OFFLINE_PACK, online, a working speech service —
 * the plan collapses from `[SERVER_DICTATE, NETWORK_RECOGNISER]` to `[NETWORK_RECOGNISER]` alone.
 * That is Google's generic engine, with none of the craft keyterms, so the phone goes back to writing
 * "double" where the artisan said "dabu" — the exact cross-surface divergence the first half of this
 * file exists to close, reopened by an unanswered question. The dictation still SUCCEEDS, which is
 * why the suppression has to be said out loud rather than merely applied: nothing on screen would
 * otherwise differ, and [DwDictationPlan.exhausted] cannot carry it because nothing is exhausted.
 * See [DwDictationPlan.suppressed].
 *
 * ── AN OPEN QUESTION THIS FILE DOES NOT SETTLE, RECORDED BECAUSE IT IS UNCOMFORTABLE ───────────
 *
 * Rung 3 is Google's NETWORK recogniser, so a REFUSED consent still hands the artisan's voice to a
 * third party — a different one. Plan §6 answer 3 gates rung 2 and says nothing whatever about rung
 * 3. Suppressing rung 3 on REFUSED — and only on REFUSED, since NOT_RECORDED would withdraw a
 * working capability from every workshop nobody has been asked about yet — is one clause in
 * `networkRung` and a DECISION FOR THE USER, not for this file. Until it is taken, no sentence here
 * claims a dictation "stays on the phone": every one of them names WHICH engine will answer instead,
 * and nothing more, because the stronger claim would be false.
 */

/** One place a dictated sentence can be turned into text. Tried in the order [dwDictationLadder] gives. */
enum class DwDictationRung {
    /**
     * The phone's own offline engine, streaming partial results as the designer speaks.
     *
     * Free, instant, and the only rung that works in a courtyard with no signal. Offered only where
     * the platform exposes an on-device recogniser to ask (API 33+, `isOnDeviceRecognitionAvailable`)
     * and nothing has told us the pack for this language is missing.
     */
    ON_DEVICE_PACK,

    /**
     * **THIS APP'S OWN SPEECH MODEL, INSTALLED ON THIS PHONE BY A DESIGNER WHO CHOSE IT.**
     *
     * Free, offline, and the only rung that can serve a language Android's own catalogue does not —
     * which on the fleet's handset is seventeen of the nineteen, Odia among them.
     *
     * OFFERED ONLY FOR THE LANGUAGES THE INSTALLED MODEL WAS MEASURED TO SERVE. A model is not a
     * capability, it is a capability in some languages ([DwModelPlan.languages]), and a rung offered
     * for a language the model cannot hear would produce either silence or confident nonsense in the
     * one place — a field on a stage screen — where nobody would check it against the audio.
     *
     * NO PARTIAL RESULTS ARE PROMISED HERE, deliberately. Whether the engine streams is a property of
     * the runtime the parallel lane is wiring, and this file will not claim a behaviour it has not
     * been told about; the control that walks the ladder shows what it gets.
     */
    APP_SPEECH_MODEL,

    /**
     * `POST /design-workshops/{id}/dictate` — the web's own fallback path, and the gated one.
     *
     * THE WORKSHOP-SCOPED URL AND NOT THE ID-LESS ONE, on both clients, because it is the only route
     * that can consult the workshop's recorded consent before handing an artisan's voice to a provider.
     *
     * The clip is recorded whole and posted, so THE WORDS DO NOT STREAM: they arrive after Stop. That
     * is a visible difference the designer has to be told about rather than left to discover, which
     * is what `DwServerDictationPanel` says, in one line, at the moment it matters.
     */
    SERVER_DICTATE,

    /**
     * Google's network recogniser — `createSpeechRecognizer`, the default engine.
     *
     * Last resort. It streams and it is free, but it carries none of the craft vocabulary, so a
     * transcript it produces can disagree with the same audio on the web. Kept because it is better
     * than nothing on a deployment with no transcription provider configured, and because on a
     * handset that could not be asked anything (API < 33) it may itself be answering from an
     * installed pack — see [dwDictationLadder].
     */
    NETWORK_RECOGNISER,
}

/**
 * WHETHER THIS WORKSHOP'S RECORDINGS MAY LEAVE THE DEVICE FOR A THIRD-PARTY PROVIDER.
 *
 * THREE STATES AND NOT A BOOLEAN, and the third is the reason this is an enum. "Nobody has asked the
 * artisan yet" and "the artisan said no" stop the send identically, but they are different facts with
 * different next moves — the first is answered by asking, the second only by the artisan changing
 * their mind — so they need different sentences (see [dwDictationNothingLeftSentence]). A boolean
 * would have had to read false for every workshop that predates the question, making a fleet of
 * never-asked workshops indistinguishable from refusals nobody ever made.
 *
 * Mirrors the server's `DwDictationConsent` Postgres enum token for token, and the mirror is the
 * point: the handset reads its own cached copy of the answer in a courtyard with no signal, and the
 * server checks the same column again on the upload. A token this app cannot recognise resolves to
 * [NOT_RECORDED] — see `dwTier3ConsentOf` — which is the same fail-closed answer the server's
 * `consent_of` gives, rather than a state no branch here has a sentence for.
 */
enum class DwTier3Consent {
    /** Nobody has been asked. The state of every workshop that predates the question. GATES RUNG 2. */
    NOT_RECORDED,

    /** The artisan agreed, a named person recorded it, and rung 2 is available. */
    GRANTED,

    /** The answer is no. GATES RUNG 2, and only a person deciding differently changes that. */
    REFUSED,
}

/**
 * Everything the ladder is allowed to know. Facts only — no engine handles, no Android types.
 *
 * [deviceRefusedLanguage] is the one input that is not a claim but a MEASUREMENT: the on-device
 * engine has already answered ERROR_LANGUAGE_UNAVAILABLE (13) for this language in this run. That
 * beats every other signal about rung 1, including [DwPackState.INSTALLED] — a pack the platform
 * lists and the engine then refuses is a pack that cannot serve this dictation, whatever the list
 * says. Remembering it is also what stops the next four hundred fields on the stage from each
 * spending a doomed round trip through the offline engine before reaching the rung that works.
 */
data class DwDictationConditions(
    /** The language's own name, for the sentences. "Odia is not installed", never "or-IN is not". */
    val languageLabel: String,
    /** What [dwPackState] made of the platform's answer, or [DwPackState.UNKNOWN] if it was not asked. */
    val packState: DwPackState,
    /** Does the platform expose an on-device recogniser at all? API 33+ and `isOnDeviceRecognitionAvailable`. */
    val onDeviceEngine: Boolean,
    /** `SpeechRecognizer.isRecognitionAvailable` — a phone with no speech service at all answers false. */
    val networkRecogniser: Boolean,
    /** [ConnectivityObserver.isOnline] — VALIDATED internet, not merely an attached interface. */
    val online: Boolean,
    /** True once the dictation route has answered 503 in this run: no provider is configured. */
    val serverRouteUnavailable: Boolean,
    /** The on-device engine has already refused this language in this run. See the class docstring. */
    val deviceRefusedLanguage: Boolean = false,
    /**
     * **A SPEECH MODEL OF THIS APP'S OWN IS INSTALLED ON THIS PHONE AND WAS MEASURED TO HEAR THIS
     * LANGUAGE.**
     *
     * Computed by `dwAppModelCoverage` — a pure function over the model catalogue and this handset's
     * install state — and passed in as one boolean, for [workshopOnServer]'s reason: this file
     * decides and never reads. Two facts are ANDed into it before it arrives, and both matter:
     * a model is *installed and verified*, and that model's own measured language list *covers this
     * tag*. Either half alone would offer a rung that cannot answer.
     *
     * **DEFAULTED TO FALSE, WHICH IS EVERY HANDSET IN THE FLEET TODAY**, and the default is doing
     * real work rather than saving a line: `DW_TIER1_CATALOGUE` is empty, no model can be installed
     * at all, and every existing call site and every existing test therefore describes the world as
     * it is without saying anything. The day a model lands, the one production construction site
     * (`conditionsNow()` in ui/designworkshop/DwDictation.kt) starts passing a real value and
     * nothing else in this file moves.
     *
     * THE HONEST-UNKNOWN RULE POINTS THE OTHER WAY HERE THAN IT DOES FOR [packState], and for
     * [packAllowsOnDevice]'s reason read backwards: an UNKNOWN Google pack resolves to "try it",
     * because trying costs a moment and skipping costs money. An unknown model install resolves to
     * "do not offer the rung", because a rung that opens a model this app cannot confirm is on the
     * phone would fail *after* the designer has spoken — the expensive kind of failure, argued at
     * length at [DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN].
     */
    val appModelServesLanguage: Boolean = false,
    /**
     * This app's own model was asked for this language in this run and could not take it.
     *
     * The exact counterpart of [deviceRefusedLanguage], for the exact reason: a measurement beats a
     * catalogue. A model listed as serving Odia that then refuses Odia on this handset is a model
     * that cannot serve this dictation, whatever the row says — **and it is data**, in the sense
     * docs/DEVICE-TIER-MEASUREMENT.md means: the catalogue said one thing and the handset did
     * another, which belongs in that document rather than in a retry loop. Remembering it is also
     * what stops the next four hundred fields on a stage from each opening a model that has already
     * said no.
     */
    val appModelRefusedLanguage: Boolean = false,
    /**
     * THIS WORKSHOP'S ANSWER TO "MAY ITS RECORDINGS LEAVE THE DEVICE" — three states, not a boolean.
     *
     * NO DEFAULT, AND THAT IS THE POINT OF HAVING IT HERE WITH NO DEFAULT. There is exactly one
     * production construction site (`conditionsNow()` in ui/designworkshop/DwDictation.kt), so a
     * required argument costs one line there and makes the fail-closed reading unforgettable. A
     * default of GRANTED would make the dangerous value the silent one — every future call site, and
     * every future surface that composes a microphone, would permit a third-party send by omission.
     *
     * The honest-unknown rule this INVERTS is [packAllowsOnDevice]'s, and the inversion is deliberate:
     * an UNKNOWN pack resolves to "try it", because trying the free engine costs a moment while
     * skipping it costs money. An unknown consent resolves to "no", because an unknown pack costs a
     * moment and an unknown consent costs a named artisan's recorded voice leaving the device for a
     * third party. Anybody "restoring consistency" between the two has to argue with this paragraph.
     */
    val tier3Consent: DwTier3Consent,
    /**
     * THIS DESIGNER'S SERVER-DICTATION ALLOWANCE FOR THE SERVER'S INDIA-TIME DAY IS USED UP.
     *
     * A MIRROR AND NOT A CONTROL. The count that decides anything is a row in the server's
     * `DwDictationDailyUsage`; process memory clears on a swipe-away, and a spend ceiling defeated by
     * a swipe-away is the one direction that costs money rather than a retry. This copy exists so the
     * ladder can drop rung 2 with ZERO BYTES UPLOADED instead of spending a six-megabyte clip per
     * field to be told the same thing — the failure `DwDictationUpload.kt` already names for the 503.
     *
     * It reads FALSE on a stale day on purpose, which is the opposite direction from consent above;
     * the argument for the asymmetry is at [DwDictationAllowance].
     */
    val dailyCapSpent: Boolean,
    /**
     * The configured ceiling, or NULL when this phone has never been told what it is.
     *
     * NULLABLE, AND THE NULL IS LOAD-BEARING. Plan §6 requires the cap be "named in words when it is
     * hit", and naming a number requires having one — but a phone that has never been told the
     * ceiling must not invent one. This is the same rule [dwDownloadCostSentence] follows when it
     * refuses to print a pack size: "any figure printed here would be invented … a made-up '≈40 MB'
     * beside a prepaid data bundle would be worse than silence." A phone learns the number from the
     * allowance the 200 carries, so the ordinary case is that it HAS it; the case that must not
     * fabricate one is a refusal learned from a 429 before any dictation of the day has succeeded.
     *
     * So both cap sentences below have a limit-known and a limit-unknown form. Meaningless unless
     * [dailyCapSpent] is true, and never read otherwise.
     */
    val dailyCapLimit: Int?,
    /**
     * IS THERE A WORKSHOP ON THE SERVER FOR THIS CLIP TO BE SENT UNDER — an id the server can load?
     *
     * NEITHER A SECOND CONSENT FLAG NOR A CONNECTION CHECK. It is the shape of the route rung 2 posts
     * to. `POST /design-workshops/{id}/dictate` is the only dictation route that reads a workshop's
     * `dictationConsent` column, so the id is what makes the gate enforceable at all — and a clip with
     * no id has nowhere to go that would check anything, because the other door checks nothing.
     *
     * FALSE IN TWO SHAPES AND BOTH MUST WITHHOLD RUNG 2:
     *
     *  * NO WORKSHOP AT ALL. The microphone is drawn into a text field's trailing icon from
     *    `FieldRenderer` and from `RichTextEditor`, and the second is a general-purpose control used in
     *    previews and on screens with no workshop behind them. There is no artisan's consent behind a
     *    dictation there, so there is nothing to send — which is the same fail-closed answer
     *    [tier3Consent] already gives for that surface, arrived at from the other direction.
     *  * A WORKSHOP THAT EXISTS ONLY ON THIS PHONE. Started in a courtyard with no signal, it holds a
     *    local id ([DW_LOCAL_ID_PREFIX]) and the server has never heard of it, so the route can only
     *    answer 404 — and it would answer it AFTER a six-megabyte upload, which is the resource this app
     *    is shortest of. Its consent may well be recorded on this device; there is nowhere to send it
     *    that could read it. The sentence names sending the workshop up, because that is the move that
     *    works.
     *
     * A BOOLEAN AND NOT THE ID ITSELF, because this file decides and never sends. The id belongs to the
     * control that uploads (`DwDictationRun.publishedWorkshop`), and a pure decision function holding
     * one would invite the next reader to assemble a URL in here — which is exactly how the pack list
     * and the ladder would end up disagreeing about what a rung is.
     */
    val workshopOnServer: Boolean,
)

/**
 * The rungs to try, in order, and — only when there are none — the sentence to show instead.
 *
 * [exhausted] is non-null exactly when [rungs] is empty. Rung 4 of the plan is not an enum entry
 * because it is not a way of transcribing anything; it is what is said when the other three are gone.
 */
data class DwDictationPlan(
    val rungs: List<DwDictationRung>,
    val exhausted: String?,
    /**
     * WHY A RUNG THIS PHONE COULD OTHERWISE HAVE USED WAS WITHHELD, WHEN OTHERS REMAIN.
     *
     * NON-FATAL, WHICH IS THE WHOLE REASON [exhausted] CANNOT CARRY IT. `exhausted` is non-null
     * exactly when [rungs] is empty — that is the invariant this file has always held and a test pins
     * it. So on a handset with a working network recogniser, an unanswered consent question produces a
     * dictation that SUCCEEDS: the words appear, in the right script, in the right box, through
     * Google's generic engine with none of the craft keyterms. Nothing is exhausted, nothing failed,
     * and without this field the designer is never told that the craft-aware path was withheld or
     * what would restore it. Plan §6's "rung 2 is unavailable AND SAYS WHY" is not met by a plan that
     * can only speak when it has nothing left.
     *
     * Null when nothing was withheld, and null when [exhausted] already says it — two sentences about
     * one fact, one of them in a strip nobody asked for, is how a designer learns to read neither.
     *
     * Shown on the LANGUAGE PANEL and not under every field: the panel is opened deliberately, while a
     * stage screen composes one microphone per prose field and a permanent strip under each of them
     * would be several hundred copies of one sentence.
     */
    val suppressed: String? = null,
) {
    /** Where a tap on the microphone starts. Null means [exhausted] is what happens instead. */
    val first: DwDictationRung? get() = rungs.firstOrNull()

    /**
     * The rung after [rung], or null when [rung] was the last.
     *
     * Looked up in the plan rather than by advancing an index the caller keeps, because the caller is
     * a composable whose state survives recomposition and whose "which rung am I on" would otherwise
     * have to be kept in step with a list it does not own.
     */
    fun after(rung: DwDictationRung): DwDictationRung? {
        val index = rungs.indexOf(rung)
        return if (index < 0) null else rungs.getOrNull(index + 1)
    }
}

/**
 * Can the phone's own offline engine be asked for this language at all?
 *
 * INSTALLED is the yes this whole rung exists for. UNKNOWN is also a yes, and that is the
 * honest-unknown rule from `DwLanguagePacks.kt` applied to an action rather than to a label: on a
 * handset that cannot be asked (API < 33), or before the check has landed, we have not been told the
 * pack is missing — and trying the free engine first costs a moment, while skipping it costs money
 * on a phone that could have done the job.
 *
 * The other five are all "the pack is not on this phone right now", which the engine reports as
 * ERROR_LANGUAGE_UNAVAILABLE. DOWNLOADING is in that group deliberately: a pack the platform has
 * accepted a request for is a pack that has not arrived, and Android 13 gives no callback to say
 * when it does (see [DW_PACK_NO_PROGRESS_ON_13]).
 */
private fun packAllowsOnDevice(state: DwPackState): Boolean =
    state == DwPackState.INSTALLED || state == DwPackState.UNKNOWN

/**
 * The ordered ladder for one language on one handset at one moment.
 *
 * ── WHY THE NETWORK RECOGNISER SOMETIMES OUTRANKS THE SERVER ──────────────────────────────────
 *
 * On a handset that exposes no on-device engine to ask — every phone below API 33, and any API 33+
 * phone whose `isOnDeviceRecognitionAvailable` answers false — `packState` is UNKNOWN and stays
 * UNKNOWN. Putting the server first there would spend provider credit on EVERY dictated sentence on
 * every one of those phones, including the ones whose default recogniser is quietly answering from a
 * pack its owner downloaded years ago, and it would take away the live partial results those phones
 * do have today. We have not been told rung 1 cannot serve the language; we have merely been unable
 * to ask. So the phone's own recogniser keeps the place it has today, ahead of anything that costs
 * money, and the server becomes the rung BEHIND it rather than the sentence that used to be printed
 * there.
 *
 * HOW MANY HANDSETS THAT IS, IS UNMEASURED. `docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md` measured
 * one Galaxy M32 on Android 13; nothing in this repository records the Android versions of the rest
 * of the fleet, so the trade above is made on a population nobody has counted.
 *
 * ── AND ON THOSE HANDSETS THE ORDER NEVER FLIPS. SAID PLAINLY, BECAUSE IT IS THE COST ─────────
 *
 * Where the phone CAN be asked, the order flips to the one plan §1 wants the moment there is a real
 * answer: a pack state from `checkRecognitionSupport`, or an on-device engine that has actually
 * refused the language ([DwDictationConditions.deviceRefusedLanguage]). On the fleet's M32 asking for
 * Odia that is exactly what happens, and it is the whole point of the change.
 *
 * Where it CANNOT be asked, neither of those two facts can ever arrive: `checkRecognitionSupport` is
 * API 33+, and the refusal is only written down for the ON-DEVICE engine (a "no" from Google's
 * network model says nothing about a pack on the phone), which on these handsets is never run. So
 * the ladder stays [NETWORK_RECOGNISER] then [SERVER_DICTATE] for the life of the app, and wherever
 * the generic engine SUCCEEDS the phone goes on producing a transcript with none of the craft
 * keyterms while the web produces one with them. That divergence is not fixed here for those
 * handsets; it is fixed for every handset that can answer a question about itself. Plan §1 reorders
 * the ladder "where rung 1 cannot serve the language at all", and this is the case where we cannot
 * establish that it cannot — the alternative reading spends money on every sentence on a population
 * whose size is, as above, unmeasured.
 *
 * ── WHY BOTH ONLINE RUNGS DROP TOGETHER WHEN THERE IS NO SIGNAL ───────────────────────────────
 *
 * Rung 3 is Google's NETWORK recogniser; with no validated connection it answers ERROR_NETWORK after
 * a wait. Rung 2 is an upload. Neither can work, so neither is offered, and the designer gets the
 * honest sentence immediately instead of watching a microphone that was never going to produce a
 * word. THE DICTATION IS NEVER QUEUED INTO [OfflineOutbox] — the designer is standing in front of a
 * field waiting for words to appear in it, and a transcript that arrives after the next sync is not
 * a dictation, it is a surprise. Plan §1: "no signal means rung 2 is simply unavailable and rung 4
 * is the honest answer."
 */
fun dwDictationLadder(conditions: DwDictationConditions): DwDictationPlan {
    val rungs = mutableListOf<DwDictationRung>()

    val deviceRung = conditions.onDeviceEngine &&
        !conditions.deviceRefusedLanguage &&
        packAllowsOnDevice(conditions.packState)

    /*
      THIS APP'S OWN MODEL. Two facts, ANDed, and neither of them is about Google.

      `appModelServesLanguage` already carries "installed AND verified AND measured to hear this
      tag"; the refusal is the same override `deviceRefusedLanguage` is for rung 1 — the engine that
      owns the model has said no, and a catalogue row does not outrank the engine that read it.

      NOTE WHAT IS NOT IN THIS CONJUNCTION: `packState`, `online`, `tier3Consent`, `dailyCapSpent`.
      None of the four has anything to say about a model sitting in this app's own storage. Consent
      in particular must never gate it — the artisan's answer is about recordings LEAVING THE
      DEVICE, and nothing leaves; gating a local model on it would withdraw an offline capability
      over a question that does not apply to it, which is the mirror of the mistake this file's own
      docstring warns about for rung 1.
    */
    val appModelRung = conditions.appModelServesLanguage && !conditions.appModelRefusedLanguage

    // Rung 3 is a network service whichever engine backs it, and UNSUPPORTED is the one state that
    // was actually measured as "this recogniser cannot do this language, offline or on" — asking it
    // anyway would spend a designer's attention on a refusal we already know is coming.
    val networkRung = conditions.networkRecogniser &&
        conditions.online &&
        conditions.packState != DwPackState.UNSUPPORTED

    /*
      RUNG 2'S EXISTENCE, IN ONE CONJUNCTION, USED IN ONE PLACE.

      The two new clauses are plan §6's answers, and they are ANDed here rather than checked at the
      call site for the reason this whole file is pure: a gate on an artisan's voice leaving the device
      has to be assertable on a desktop JVM, over every combination, without a microphone.

      NOTHING ELSE IN THIS FUNCTION MOVES WHEN THEY BITE. Rung 3's position does not depend on rung 2
      — `phoneNotYetAsked` picks which of two mutually exclusive lines appends NETWORK_RECOGNISER and
      neither of them reads `serverRung` — so a suppressed plan is today's plan with rung 2 excised and
      every other rung exactly where it was. That is what makes the fallback the one plan §6 promises:
      [ON_DEVICE_PACK?] then [NETWORK_RECOGNISER?] then the sentence.

      AND THE COST IS IN THE FILE'S OWN DOCSTRING, not only in the plan document: on the fleet's M32
      asking for Odia this collapses to Google's generic engine alone, and the phone goes back to
      writing "double" for "dabu".

      THE FOURTH CLAUSE IS THE ROUTE'S OWN, AND IT IS THE ONE THAT MAKES THE OTHERS BINDING. Rung 2
      posts to `POST /design-workshops/{id}/dictate`; with no workshop id there is nothing to post to
      that would consult a consent column, and the only URL that accepts a clip without one is the door
      this app stopped using. So a missing id refuses the rung here, in the pure function, over every
      combination, rather than being caught at the upload — which is where a "fail closed" that lives
      only at the call site stops being true the moment a second call site exists. See
      [DwDictationConditions.workshopOnServer].
    */
    val serverRung = conditions.online &&
        !conditions.serverRouteUnavailable &&
        conditions.tier3Consent == DwTier3Consent.GRANTED &&
        !conditions.dailyCapSpent &&
        conditions.workshopOnServer

    // The handset that could not be asked anything and whose own recogniser has not failed yet. See
    // the docstring: this is the ONLY case where the generic engine is tried before the craft one.
    val phoneNotYetAsked = conditions.packState == DwPackState.UNKNOWN &&
        !conditions.onDeviceEngine &&
        !conditions.deviceRefusedLanguage

    if (deviceRung) rungs += DwDictationRung.ON_DEVICE_PACK
    /*
      OUR OWN MODEL GOES SECOND, ABOVE BOTH ONLINE RUNGS AND ABOVE THE `phoneNotYetAsked` SWAP.

      The swap below exists to protect handsets that cannot be asked about packs from having their
      free, streaming recogniser skipped in favour of a paid one. That argument is about a rung whose
      availability is a GUESS; ours is not a guess — it is a model this app verified on this phone in
      this run and measured against this language — so it takes precedence over both arms of it. On
      such a handset the plan becomes [ON_DEVICE_PACK?], [APP_SPEECH_MODEL], [NETWORK_RECOGNISER],
      [SERVER_DICTATE], and the phone stops spending anything at all on the languages our model
      covers.
    */
    if (appModelRung) rungs += DwDictationRung.APP_SPEECH_MODEL
    if (phoneNotYetAsked && networkRung) rungs += DwDictationRung.NETWORK_RECOGNISER
    if (serverRung) rungs += DwDictationRung.SERVER_DICTATE
    if (!phoneNotYetAsked && networkRung) rungs += DwDictationRung.NETWORK_RECOGNISER

    return DwDictationPlan(
        rungs = rungs,
        exhausted = if (rungs.isEmpty()) dwDictationNothingLeftSentence(conditions) else null,
        // Only when something is LEFT. With nothing left the exhausted sentence above already names
        // the same fact, and two sentences about one withheld rung is how a designer learns to read
        // neither of them. See [DwDictationPlan.suppressed].
        suppressed = if (rungs.isEmpty()) null else dwSuppressedServerRungSentence(conditions),
    )
}

/**
 * The craft-aware rung was withheld and another one is still going to answer. Say which and why.
 *
 * ── WHY THIS IS SILENT FOR THE OTHER TWO WAYS RUNG 2 CAN VANISH ───────────────────────────────
 *
 * NO CONNECTION: rung 2 is gone with the connection, and so is rung 3, so either the plan is empty and
 * the [dwDictationNothingLeftSentence] arms for it are the whole story, or rung 1 is serving the
 * language offline — in which case nothing was withheld from anybody and a note about consent would be
 * a false cause printed beside a control that is working perfectly.
 *
 * THE SERVER SAID IT HAS NO PROVIDER (503): that is a property of the deployment, it is already
 * reported at the moment it happens ([DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN]), and it is not a
 * suppression — there was no craft-aware transcription available to withhold.
 *
 * ── AND WHY NOT ONE OF THEM SAYS THE RECORDING "STAYS ON THE PHONE" ───────────────────────────
 *
 * Because it may not. The rung that answers instead is usually rung 3, Google's NETWORK recogniser, and
 * a consent note that implied the audio never left the device would be the one wrong claim this feature
 * cannot afford to make. Every sentence therefore says "the phone's speech recogniser" — which is what
 * answers, whether that is an installed pack or Google's engine — and names the CONSEQUENCE, which is
 * the craft vocabulary, rather than making a claim about where the audio goes. See the open question in
 * this file's docstring.
 *
 * ── WHAT [DwDictationRung.APP_SPEECH_MODEL] DID AND DID NOT CHANGE ABOUT THESE FIVE ───────────
 *
 * On a handset where this app's own model serves the language, the engine that answers instead of the
 * server is OURS rather than Google's — and every sentence below still holds, because each names the
 * CONSEQUENCE (no craft vocabulary, so "double" for "dabu") rather than the engine, and that
 * consequence is equally true of our model: the keyterm list lives on the server and nowhere else.
 * **The phrase "the phone's speech recogniser" is nonetheless loose in that case** and is left alone
 * deliberately rather than forked five ways: a sixth sentence per arm, differing only in which local
 * engine is named, is five more strings to keep in step for a distinction the designer cannot act on.
 * Whoever gives our model a distinct voice on screen should revisit this paragraph with them.
 */
private fun dwSuppressedServerRungSentence(conditions: DwDictationConditions): String? {
    if (!conditions.online || conditions.serverRouteUnavailable) return null
    return when {
        // CONSENT BEFORE THE CAP, always, for the reason [dwDictationNothingLeftSentence] gives at
        // length: tomorrow's allowance cannot open a rung an unanswered consent question has closed.
        conditions.tier3Consent == DwTier3Consent.NOT_RECORDED ->
            "Nobody has recorded yet whether recordings from this workshop may be sent to the " +
                "transcription service, so a dictation here goes to the phone's speech recogniser " +
                "instead — which has none of the craft vocabulary, and writes “double” where the " +
                "artisan said “dabu”. Record the artisan's answer on this workshop's own screen to " +
                "change that."

        conditions.tier3Consent == DwTier3Consent.REFUSED ->
            "This workshop's recordings may not be sent to the transcription service — that is the " +
                "answer on record — so a dictation here goes to the phone's speech recogniser " +
                "instead, which has none of the craft vocabulary. If the artisan has since agreed, " +
                "change that answer on the workshop's own screen; nothing on this field can."

        /*
          THE WORKSHOP IS NOT ON THE SERVER, SO THERE IS NO GATED ROUTE TO POST TO.

          BELOW THE CONSENT ARMS AND ABOVE THE CAP'S, and the placement is the field advice rather than
          a tidy ordering. Where both are missing — a courtyard workshop nobody has been asked about —
          the consent is the half that must be recorded WITH THE ARTISAN PRESENT, and it can be recorded
          with no signal at all; sending the workshop up needs signal and can be done afterwards from
          anywhere. Naming the send first would have a designer walk out for a bar of signal, come back,
          and find the artisan gone and the question still unasked.

          It reads as GRANTED here, because the consent arms above have already spoken otherwise — so
          this sentence is about a workshop whose answer IS yes and which has nowhere yet to send it.
        */
        !conditions.workshopOnServer ->
            "This workshop has not been sent to the server yet, so there is nothing up there to check " +
                "the artisan's recorded answer against and a dictation here goes to the phone's speech " +
                "recogniser instead — which has none of the craft vocabulary, and writes “double” " +
                "where the artisan said “dabu”. Send the workshop up from the workshops list, with the " +
                "“Send to server” button on its card, and this comes back."

        // An allowance of none is a setting, not a spent day — see the same arm in
        // [dwDictationNothingLeftSentence] for why "all 0 of today's" may never be printed.
        conditions.dailyCapSpent && conditions.dailyCapLimit == 0 ->
            "This server is not sending any dictation to the transcription service at the moment — " +
                "the daily allowance is set to none — so a dictation here goes to the phone's speech " +
                "recogniser instead, which has none of the craft vocabulary. Whoever runs the server " +
                "can raise the allowance; nothing on this phone can."

        conditions.dailyCapSpent && conditions.dailyCapLimit != null ->
            "You have used all ${conditions.dailyCapLimit} of today's dictations that go to the " +
                "transcription service, so a dictation here goes to the phone's speech recogniser " +
                "instead, which has none of the craft vocabulary. The allowance starts again after " +
                "midnight India time, which is the server's day and not this phone's."

        conditions.dailyCapSpent ->
            "You have used up today's allowance of dictations that go to the transcription service, " +
                "so a dictation here goes to the phone's speech recogniser instead, which has none " +
                "of the craft vocabulary. The allowance starts again after midnight India time, " +
                "which is the server's day and not this phone's."

        else -> null
    }
}

/**
 * What to say when there is no rung left — plan rung 4, "type it in", said at the RIGHT time.
 *
 * It used to be said far too early: any handset without the language pack got "Dictation stopped
 * unexpectedly (code 13). Type the answer in, or try again" on the first tap, and no number of
 * further taps could ever download a pack. Every sentence below therefore names the actual state of
 * this phone and the next move that can work, and none of them says "try again" unless trying again
 * is capable of a different outcome.
 *
 * PUBLIC because it is needed at two moments, not one. [dwDictationLadder] uses it when the plan is
 * empty before anything was tried; the control uses it when a walk has USED every rung on its plan,
 * which is a state the plan itself cannot see. Both are the same sentence about the same phone.
 *
 * ── HOW MANY SHAPES THIS IS REACHABLE IN, WHICH THIS DOCSTRING GOT WRONG FOR ONE COMMIT ────────
 *
 * It used to say: "Only reachable in two shapes, because a device that is online with a configured
 * server always has rung 2: there is no connection, or the server said it is not configured and
 * nothing else remains." THAT IS NO LONGER TRUE, and correcting it is more urgent than an ordinary
 * stale comment: it is exactly the reasoning a later reader would use to decide one of the branches
 * below is unreachable and delete it. An online handset with a perfectly configured server now has no
 * rung 2 in three further shapes — the workshop's consent question is unanswered or answered no, this
 * designer's daily allowance is spent, and the workshop has no server record for the gated route to be
 * asked about ([DwDictationConditions.workshopOnServer]). So there are FIVE reasons rung 2 can be
 * missing here, and each one gets its own arm because each one has a different next move.
 */
fun dwDictationNothingLeftSentence(conditions: DwDictationConditions): String {
    val label = conditions.languageLabel
    return when {
        /*
          NO SIGNAL, AND THIS APP'S OWN MODEL — THE ONE THING HERE THAT WAS SUPPOSED TO WORK WITHOUT
          ONE — HAS REFUSED THE LANGUAGE.

          ABOVE THE THREE ARMS BELOW, WHICH IS THE ONE PLACE THIS FILE PUTS SOMETHING AHEAD OF THE
          CONNECTION. Their reason for going first is that with no signal the connection is the true
          blocker and naming anything else would be a false cause. That reasoning does not hold here:
          a model installed on this phone needs no connection, so the connection is NOT what stopped
          it, and telling a designer who paid for that download that "this needs a connection" would
          be this app blaming the courtyard for its own model's failure.

          IT NAMES NO RETRY, because trying again in the same run reaches the same refusal — the
          engine has already been asked. It names the report instead: a model measured as serving
          this language and refusing it on a real handset is exactly the contradiction
          docs/DEVICE-TIER-MEASUREMENT.md exists to collect.
        */
        !conditions.online && conditions.appModelRefusedLanguage ->
            "This app's own speech model is on this phone and would not take $label just now, and " +
                "there is no connection to send the words anywhere else. Type the answer in. This " +
                "is worth reporting: the model was measured as serving $label, so it refusing it on " +
                "this handset is a fault in this app rather than anything you did."

        /*
          NO SIGNAL, THE PHONE HAS AN OFFLINE ENGINE, AND A PACK FOR THIS LANGUAGE CAN ACTUALLY BE
          FETCHED. Only then does this sentence send anybody to the settings list.

          The rule is [dwPackOffer]'s and not a second copy of it, asked with [DwConnection.NONE]
          because that is the connection we are in: it answers NO_CONNECTION for exactly the one
          state a download may ever be offered for (DOWNLOADABLE) and UNAVAILABLE for NETWORK_ONLY,
          NO_OFFLINE_PACK and UNSUPPORTED — "no offline pack is coming, and no button can change
          that". Sharing the predicate is what stops this sentence promising a download the settings
          list would refuse to draw a button for.

          THE FAILURE THIS REPLACES was ours, not an old one: the first version of this branch told
          every offline designer to "add the pack from Settings › Dictation languages", which is
          advice that cannot work for the seventeen — ODIA AMONG THEM — because there is no pack for
          them on Google's catalogue to add (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md), and which
          named a menu entry that does not exist. The card is headed "Offline dictation languages"
          (`ui/AppearanceScreen.kt`), and this file's own docstring says none of these sentences may
          suggest an action incapable of a different outcome.
        */
        !conditions.online && conditions.onDeviceEngine &&
            dwPackOffer(conditions.packState, DwConnection.NONE) == DwPackOffer.NO_CONNECTION ->
            "The $label pack is not on this phone, and without it dictation needs a connection — " +
                "there is none here. Type the answer in, and add the pack from Settings › Offline " +
                "dictation languages when you next have signal."

        // No signal, the phone has an offline engine, and there is NO pack for this language to add:
        // either the platform's catalogue has none (the seventeen), or the engine has refused the
        // language despite the list claiming it. Both leave the same next move, and neither leaves
        // anything to tap — so the sentence names the connection and stops, rather than sending a
        // designer to a list that will offer them nothing when they get there.
        !conditions.online && conditions.onDeviceEngine ->
            "This phone's own recogniser has no offline $label to work from, so dictation in $label " +
                "needs a connection and there is none here. Type the answer in, and dictate the rest " +
                "where there is signal."

        // No signal, and this phone cannot be asked what it has (API < 33) or has no engine of its
        // own. Claiming the pack is missing would be inventing the one fact we do not have, so the
        // sentence says what is certain — this needs a connection — and points at the keyboard, whose
        // own microphone is a separate offline recogniser on many handsets.
        !conditions.online ->
            "Dictation in $label on this phone needs a connection and there is none. Your keyboard's " +
                "own microphone may have an offline language pack; otherwise type the answer in and " +
                "dictate the rest later."

        /*
          ── THE FIVE ARMS RUNG 2'S GATES ADDED, AND WHY THEY SIT EXACTLY HERE ──────────────────

          FOUR OF THEM ARE PLAN §6's — two consent states and two shapes of spent allowance — and the
          fifth is the route's own: a workshop with no server record, which arrived when rung 2 moved
          onto `POST /design-workshops/{id}/dictate`. Everything below applies to all five.

          BELOW THE `!online` TRIO, WHICH IS UNTOUCHED. With no connection rung 2 is already gone on
          the connection alone, and so is rung 3; the connection is the true blocker, and naming
          consent or an allowance there would be a false cause printed over a real one. A designer
          told "record the artisan's answer" in a courtyard would walk to the workshop screen, record
          it, come back, and still have no dictation — because what they actually needed was signal.

          ABOVE THE TWO ARMS THAT FOLLOW, BECAUSE THOSE TWO BLAME A SERVER THAT IS FINE. Both of them
          assert "the server has no transcription service configured" and send the designer to "tell
          whoever runs the server" — advice that, on a correctly configured deployment held up by an
          unanswered consent question or a spent allowance, dispatches somebody to bother an
          administrator when the fix was one tap on their own workshop screen, or tomorrow. That is
          the failure these five arms exist to replace.

          AND EACH ONE IS GUARDED ON `!serverRouteUnavailable`, which is the subtle half. When the
          server HAS said it has no provider, consent is not the blocker that matters: recording it
          changes nothing, and this function's own standing rule is that every sentence must name a
          next move CAPABLE of a different outcome. So a 503-ed deployment keeps the two arms below,
          which name the only person who can fix it, and these five speak only where rung 2 would
          otherwise have worked.

          CONSENT BEFORE THE CAP where both are true, because they are not symmetrical: an allowance
          returns at midnight without anybody doing anything, while an unanswered consent question
          returns never. Naming the cap first would promise a designer that tomorrow fixes it, and
          tomorrow would arrive with the rung still closed.
        */
        !conditions.serverRouteUnavailable &&
            conditions.tier3Consent == DwTier3Consent.NOT_RECORDED ->
            "There is nothing left on this phone that can write $label down: its own recogniser " +
                "cannot take this language, and nobody has recorded yet whether recordings from " +
                "this workshop may be sent to the transcription service. Type the answer in. Then " +
                "open this workshop's own screen and record the artisan's answer to that question — " +
                "until somebody does, this stays unavailable, and no amount of tapping here will " +
                "change it."

        // ANSWERED, AND THE ANSWER IS NO. The next move is A PERSON DECIDING and never a retry —
        // which is why this says nothing about trying again. It also does not say "ask the artisan",
        // because they have already been asked: telling somebody to go and ask a question that is on
        // record is how a designer learns to stop reading these sentences.
        !conditions.serverRouteUnavailable &&
            conditions.tier3Consent == DwTier3Consent.REFUSED ->
            "There is nothing left on this phone that can write $label down: its own recogniser " +
                "cannot take this language, and this workshop's recordings may not be sent to the " +
                "transcription service — that is the answer on record. Type the answer in. If the " +
                "artisan has since agreed, change that answer on the workshop's own screen; nothing " +
                "on this field can."

        /*
          THE WORKSHOP HAS NO SERVER RECORD, SO THE GATED ROUTE HAS NO id TO REFUSE OR ALLOW.

          THE SAME PLACE AS ITS PANEL ARM, for that arm's reason: consent is recorded with the artisan
          and needs no signal, sending the workshop up needs signal and no artisan. And it is guarded on
          `!serverRouteUnavailable` like the four §6 arms, because a deployment with no provider is not
          fixed by sending a workshop anywhere.

          THIS SENTENCE IS THE ONE COST OF CLOSING THE UNCONSENTED DOOR, said in the place a designer
          meets it. Until this change the clip went to `POST /design-workshops/dictate` — no id, no
          consent column consulted — so a courtyard workshop dictated fine and an artisan's voice
          reached the provider with nobody having answered for it. It now waits for the workshop to
          exist where the answer can be read.
        */
        !conditions.serverRouteUnavailable && !conditions.workshopOnServer ->
            "There is nothing left on this phone that can write $label down: its own recogniser " +
                "cannot take this language, and this workshop has not been sent to the server, so the " +
                "transcription service cannot be asked for it either. Type the answer in. Send the " +
                "workshop up from the workshops list — the “Send to server” button on its card — and " +
                "dictation here works wherever there is signal."

        /*
          AN ALLOWANCE OF NONE IS A SETTING AND NOT A SPENT DAY, and it needs its own sentence for a
          reason the server's own refusal states first: "You have used all 0 of today's dictations" is
          prose that reads as a bug, and a designer who has recorded nothing all morning being told they
          have used up their allowance would reasonably conclude the app is broken and stop trusting the
          next message. A ceiling of zero means somebody switched server dictation off, so this is the
          one cap arm whose next move really is an administrator — and it says so instead of promising
          that tomorrow fixes it, which it would not.
        */
        !conditions.serverRouteUnavailable &&
            conditions.dailyCapSpent && conditions.dailyCapLimit == 0 ->
            "This server is not sending any dictation to the transcription service at the moment — " +
                "the daily allowance is set to none — and this phone's own recogniser cannot take " +
                "$label. Type the answer in, and ask whoever runs the server to raise the allowance; " +
                "nothing on this phone can."

        // THE ALLOWANCE, NAMED AS A NUMBER BECAUSE THE PHONE HAS ONE. Plan §6 asks for the cap to be
        // "named in words when it is hit", and a refusal that will not say how many is one nobody can
        // plan a day around.
        !conditions.serverRouteUnavailable &&
            conditions.dailyCapSpent && conditions.dailyCapLimit != null ->
            "You have used all ${conditions.dailyCapLimit} of today's dictations that go to the " +
                "transcription service, and this phone's own recogniser cannot take $label — so " +
                "there is nothing left here that can write it down until tomorrow. Type the answer " +
                "in; the allowance starts again after midnight India time, which is the server's " +
                "day and not this phone's."

        // THE SAME REFUSAL FROM A PHONE THAT WAS NEVER TOLD THE CEILING. It does not invent one: the
        // rule is [dwDownloadCostSentence]'s, where a pack's size is deliberately not named because
        // any figure printed there would be made up. A number this phone has not been told is worth
        // less than the sentence without it.
        !conditions.serverRouteUnavailable && conditions.dailyCapSpent ->
            "You have used up today's allowance of dictations that go to the transcription service, " +
                "and this phone's own recogniser cannot take $label — so there is nothing left here " +
                "that can write it down until tomorrow. Type the answer in; the allowance starts " +
                "again after midnight India time, which is the server's day and not this phone's."

        /*
          ONLINE, AND OUR OWN MODEL REFUSED THE LANGUAGE TOO.

          LAST OF THE ARMS THAT NAME A CAUSE, BELOW EVERY ONE THAT NAMES SOMETHING THE DESIGNER CAN
          DO. Recording a consent, sending the workshop up and waiting for tomorrow's allowance all
          re-open a rung; our model refusing does not, and there is nothing on any screen that
          changes it. An arm that names an unfixable cause ahead of a fixable one sends somebody to
          report a bug when one tap on their own workshop screen would have restored the dictation.

          It is still above the two server arms below, because those two say "the server has no
          transcription service configured" and send the designer to an administrator — true, but
          this phone also has a model that was measured to serve this language and did not, and
          leaving that out of the sentence would lose the only new information in it.
        */
        conditions.appModelRefusedLanguage ->
            "This app's own speech model would not take $label on this phone, and there is nothing " +
                "else left that can write it down — the server has no transcription service " +
                "configured either. Type the answer in. Tell whoever runs the server about the " +
                "transcription service, and report the model as well: it was measured as serving " +
                "$label, and refusing it here is a fault in this app."

        // Online, and the server has said it has no transcription provider. NEVER SILENCE — a 503
        // swallowed here reads to the designer as "the phone heard nothing", which sends them
        // speaking louder at a control that was never going to answer.
        conditions.packState == DwPackState.UNSUPPORTED ->
            "This phone's speech recogniser does not offer $label at all, and the server has no " +
                "transcription service configured to do it instead. Pick another language, or type " +
                "the answer in — and tell whoever runs the server that dictation is not configured."

        else ->
            "There is nothing left on this phone that can write $label down: its own recogniser " +
                "cannot take this language and the server has no transcription service configured. " +
                "Type the answer in, and tell whoever runs the server that dictation is not configured."
    }
}

/*
 * `dwDictationServerNote` STOOD HERE AND IS GONE, WITH THE PANEL PARAGRAPH IT COMPOSED.
 *
 * It answered "why is this dictation on rung 2", naming the pack state, and appended sixty words
 * about the craft keyterm list being handed to the transcription model. Measured on the fleet's
 * handset, its panel ran to 92 words where the web spends 15 in the same situation.
 *
 * BOTH HALVES FAILED THE SAME TEST. The keyterm paragraph is provenance — how the app works — and
 * nothing a designer reads there changes what they do. The "why" half is an implementation detail:
 * they chose a language and pressed a button, and which of four rungs answered is not a decision
 * they make. `DwServerDictationPanel` now says the one thing that is genuinely different about this
 * rung — the words arrive on Stop — which is the same line, for the same reason, that the web prints.
 *
 * The ladder still DECIDES all of this; it has simply stopped narrating it. See principle 1.
 */

// ---------------------------------------------------------------------------------------------
// The size and length of one dictated clip
// ---------------------------------------------------------------------------------------------

/**
 * MIRRORED FROM THE SERVER: `DICTATION_MAX_BYTES` in backend/app/api/routes/design_workshops.py,
 * where it reads `6 * 1024 * 1024`. Six megabytes.
 *
 * Cited by NAME and not by line: that module is edited often enough that a line number here would
 * be pointing at somebody else's code within a week, and a stale citation is worse than none — it
 * sends the next reader to check a constant against a line that no longer holds it.
 *
 * Mirrored rather than discovered by being refused, because the refusal costs exactly the thing this
 * app is most short of. The upload happens over whatever bar of mobile signal a district town has;
 * sending six megabytes and being told at the end that six megabytes is too many spends the scarce
 * resource to learn a constant. The server still enforces it — this is a second check, not the only
 * one — and if the two ever disagree the server wins and its 413 message is shown verbatim.
 *
 * The server's own reasoning for the number, worth keeping in view here: the cap is what stops a
 * synchronous endpoint from becoming a back door into the transcription queue. A forty-minute
 * interview belongs in the media flow, which is queued off-peak with retries; this holds a worker for
 * the whole provider round trip.
 */
const val DW_DICTATION_MAX_BYTES: Long = 6L * 1024 * 1024

/**
 * How long the recorder is allowed to run before it stops itself. Four minutes.
 *
 * WHY A CAP AT ALL: a designer who taps the microphone and is then pulled into a conversation would
 * otherwise come back to a clip the server refuses, and discover after speaking that none of it was
 * kept. Stopping at the cap and sending what was captured is better than refusing everything —
 * losing the tail of a passage is recoverable by dictating the rest, losing the passage is not.
 *
 * WHY FOUR MINUTES: arithmetic, not measurement. At the [DW_DICTATION_BITS_PER_SECOND] this recorder
 * asks for, four minutes is 4 000 bytes/s × 240 s = 960 000 bytes, about a sixth of
 * [DW_DICTATION_MAX_BYTES] — margin enough for MPEG-4 container overhead and for a device whose
 * encoder ignores the requested bit rate and picks its own. WHAT AN ARBITRARY HANDSET'S AAC ENCODER
 * ACTUALLY PRODUCES IS UNMEASURED, which is why the byte cap is also asked of the recorder itself and
 * checked again on the finished file before anything is uploaded.
 */
const val DW_DICTATION_MAX_MILLIS: Int = 4 * 60 * 1000

/**
 * What the recorder asks the encoder for: 32 kbit/s, one channel, 16 kHz.
 *
 * A dictated sentence is speech, not music: one voice, close to the phone, in a band a telephone has
 * carried for a century. Asking for stereo or 44.1 kHz would multiply what a designer's prepaid
 * bundle pays to carry the same words. WHAT EACH PROVIDER DOES WITH A HIGHER RATE IS UNMEASURED —
 * these are speech-capture defaults, not a tuning result. Declared here rather than inside the
 * recorder so the arithmetic above and the encoder settings cannot drift apart.
 */
const val DW_DICTATION_BITS_PER_SECOND: Int = 32_000
const val DW_DICTATION_SAMPLE_RATE_HZ: Int = 16_000
const val DW_DICTATION_CHANNELS: Int = 1

/**
 * Is this finished clip too big to send, and if so what does the designer read?
 *
 * Null means "send it". Checked BEFORE the upload — see [DW_DICTATION_MAX_BYTES] — and expected
 * never to fire, because [DW_DICTATION_MAX_MILLIS] and the recorder's own file-size limit should
 * have stopped the recording first. It exists for the case where they did not: an encoder that
 * ignored the bit rate, or a device whose MediaRecorder refused `setMaxFileSize` (some do, and the
 * call is wrapped for exactly that reason).
 */
fun dwDictationOversize(bytes: Long): String? {
    if (bytes <= DW_DICTATION_MAX_BYTES) return null
    val megabytes = DW_DICTATION_MAX_BYTES / (1024 * 1024)
    return "That recording is longer than the ${megabytes} MB a dictated clip may be. Dictate it in " +
        "shorter passages, or record it as workshop audio instead — that is transcribed in the " +
        "background and comes back onto the stage."
}

/** An empty file after a Stop: the microphone was open and nothing arrived through it. */
const val DW_DICTATION_NOTHING_RECORDED: String =
    "Nothing was recorded. Check that no other app is holding the microphone, hold the phone closer " +
        "and try again."

/**
 * The server transcribed the clip and found no words in it.
 *
 * A DIFFERENT SENTENCE FROM A FAILED UPLOAD, deliberately. This one means the round trip worked and
 * the audio had nothing in it a model could read, so the next move is about the microphone and the
 * room; a failed upload's next move is about the connection. One sentence for both would send a
 * designer looking for signal in a quiet room, or for a quiet room in a place with no signal.
 */
const val DW_DICTATION_NO_WORDS: String =
    "The recording went to the server and came back with no words in it. Speak closer to the phone, " +
        "away from the loom if you can, and try again — or type the answer in."

/**
 * The 503 from the dictation route: this deployment has no transcription provider configured.
 *
 * NEVER SHOWN AS SILENCE, and never as an empty result. The route raises 503 rather than answering
 * 200 with an empty string for the same reason the identity-card route does — an empty 200 is
 * indistinguishable from "you said nothing", and a designer who reads it that way spends the
 * afternoon speaking louder at a control that has no provider behind it at all.
 *
 * It names an administrator because that is genuinely the only person who can fix it: the keys live
 * in server settings, and no amount of anything on this phone will change the answer.
 */
const val DW_DICTATION_NOT_CONFIGURED: String =
    "The server has no transcription service configured, so it cannot write this recording down. " +
        "Type the answer in, and ask whoever runs the server to configure dictation."

/**
 * The same 503, where a rung still remains behind it — and why that rung is not simply started.
 *
 * A HAND-OVER AFTER SOMEBODY HAS SPOKEN IS NOT THE SAME ACT AS A HAND-OVER BEFORE THEY HAVE. Rungs 1
 * and 3 refuse a language within a moment of the microphone opening, so stepping to the next rung
 * costs the designer nothing and is rightly silent. Rung 2 fails at the END: they have said their
 * forty words, pressed Stop, and the clip has been recorded, uploaded and thrown away. Starting the
 * next rung there re-opens the microphone at a person who has finished speaking, and what they
 * actually get is a few seconds of silence followed by "No speech was heard" — their passage gone,
 * and no explanation anywhere of what became of it.
 *
 * So the walk stops and says this instead. The 503 is remembered for the run, so the very next tap
 * starts on the rung that would have been silently entered here, with the designer speaking into it
 * deliberately. Plan §1 asks that this path be "shown as 'not configured', never as silence"; hiding
 * it whenever another rung existed was silence with extra steps.
 */
const val DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN: String =
    "The server has no transcription service configured, so it could not write that recording down. " +
        "Nothing was saved. Tap the microphone and say it again — the next attempt goes to this " +
        "phone's own recogniser, and the words will appear as you speak."

/**
 * THE DAILY ALLOWANCE RAN OUT WHILE THE DESIGNER WAS SPEAKING, and a rung still remains behind it.
 *
 * ── WHY THIS EXISTS AT ALL, WHEN THE LADDER ALREADY REFUSES BEFORE RECORDING ───────────────────
 *
 * The mirror on this phone is a mirror: it is written from the allowance the last dictation's answer
 * carried, so the FIRST refusal of a day is always learned the expensive way — the clip is recorded,
 * uploaded, and answered with a 429. Every field after it is refused before the microphone opens,
 * which is what the mirror is for; this sentence is for the one that paid.
 *
 * ── AND WHY THE WALK STOPS RATHER THAN QUIETLY STARTING THE NEXT RUNG ──────────────────────────
 *
 * [DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN]'s reason, in full and unchanged: a hand-over AFTER somebody
 * has spoken is not the same act as one before. Rung 2 fails at the END — forty words said, Stop
 * pressed, the clip recorded, uploaded and thrown away — so silently opening Google's engine there
 * re-opens the microphone at a person who has finished speaking, and what they get is a few seconds of
 * silence followed by "No speech was heard", their passage gone and nothing anywhere accounting for
 * it. So the refusal is remembered, the walk stops, and the very next tap starts on the rung that
 * would have been entered here — with the designer speaking into it deliberately.
 *
 * ── WHY IT NAMES NO NUMBER, WHERE THE SERVER'S OWN 429 DOES ────────────────────────────────────
 *
 * The server's `detail` for a 429 is field copy and it names the limit ("all 40 of today's"). It is
 * shown verbatim where nothing remains, which is the branch that has nothing to add to it. This
 * constant is for the branch that HAS something to add — say it again, into a different engine — and a
 * number cannot be spliced into it, because the 429 arrives as a sentence rather than as an allowance
 * and this file may not invent one. The [dwDictationNothingLeftSentence] arms name the number
 * whenever the phone has been told it; here the added instruction is worth more than the figure.
 */
const val DW_DICTATION_CAP_SPENT_SAY_AGAIN: String =
    "Today's allowance of dictations that go to the transcription service is used up, so that " +
        "recording could not be written down. Nothing was saved. Tap the microphone and say it " +
        "again — the next attempt goes to this phone's own recogniser, and the words will appear as " +
        "you speak."

/**
 * THE SERVER'S CONSENT GATE REFUSED THE CLIP (409) AND SENT NO SENTENCE WITH IT.
 *
 * ── WHEN THIS IS REACHED AT ALL, WHICH IS THE PART WORTH KNOWING ───────────────────────────────
 *
 * Almost never, and never in place of the server's own words. `POST /design-workshops/{id}/dictate`
 * refuses a workshop whose `dictationConsent` is not GRANTED with a 409 and a `detail` written for the
 * person holding the phone — two different sentences, one for "nobody has asked the artisan" and one
 * for "the artisan said no", because the next moves differ — and the control prints that `detail`
 * verbatim. This is the fallback for a 409 whose body carried none: a proxy that rewrote it, or a
 * future refusal on this route that forgets to write one.
 *
 * ── AND WHY IT REACHES THE PHONE AT ALL, WHEN THE LADDER ALREADY GATES ON CONSENT ───────────────
 *
 * Because the phone's copy of the answer can be WRONG, and that is the whole reason the gate had to
 * move onto the server's route. The ladder reads a mirror published from a draft; the server reads its
 * own column. A consent recorded in a courtyard and never pushed up (`StageIndexScreen.dwPushUnsent`
 * records that residual in as many words), or an answer withdrawn in a browser since this phone last
 * synced, and the two disagree — the phone offers rung 2, the server refuses it. That refusal is the
 * gate doing its job, and the sentence therefore names the screen that makes the two agree.
 *
 * ── WHAT IT MAY NOT SAY ────────────────────────────────────────────────────────────────────────
 *
 * WHICH of the two states the server found, because a 409 with no body does not say — inventing
 * "nobody has asked" for a workshop where the artisan refused would send a designer to ask a question
 * that is on record. And never "try again": nothing about repeating an upload changes a consent, and
 * the next move is a person deciding.
 *
 * ── AND ONE THING IT CAN GET WRONG, WHICH IS CHEAPER TO STATE THAN TO FIX ──────────────────────
 *
 * The gate is not the only 409 that route raises: `load_workshop_or_404(..., for_edit=True)` answers one
 * for a soft-deleted workshop, reachable by an admin alone. WITH A BODY that costs nothing — the server's
 * sentence names the restore and is shown verbatim — but a BODILESS one would land here and describe a
 * deleted workshop as one whose consent answer is not a yes. It needs a proxy that strips the body AND an
 * admin dictating into a deleted workshop, and the alternative is a sentence vague enough to cover both,
 * which would blunt the one every ordinary refusal reads. See [DwDictationConsentRefused].
 */
const val DW_DICTATION_CONSENT_REFUSED: String =
    "The server would not send that recording to the transcription service: this workshop's answer to " +
        "whether its recordings may go there is not a yes up there. Nothing was saved. Type the answer " +
        "in, and open this workshop's own screen — the answer this phone holds is carried up from " +
        "there, and whatever the server holds is shown there."

/**
 * The provider chain answered RATE_LIMITED — 429, or a provider's own 503 "overloaded".
 *
 * ITS OWN SENTENCE, AND NOT THE SERVER'S. The chain's message for this case ends "…will retry
 * automatically", which is true of the transcription QUEUE, where a throttled clip is requeued behind
 * a growing cooldown without consuming an attempt. Nothing retries a dictation: the endpoint is
 * synchronous and stores nothing, so passing that message through would promise a designer a
 * transcript that is never coming.
 */
const val DW_DICTATION_BUSY: String =
    "The transcription service is busy just now and could not take this recording. Wait a moment and " +
        "dictate it again, or type the answer in — nothing is queued, so it will not arrive later."

/** The upload itself did not land. Names the connection, because that is what changed. */
const val DW_DICTATION_UPLOAD_FAILED: String =
    "The recording could not be sent to the server — the connection dropped. Nothing was saved. Try " +
        "again where there is signal, or type the answer in."

/**
 * The round trip worked, the provider chain ran, and it could not produce text.
 *
 * SEPARATE FROM [DW_DICTATION_NO_WORDS], which means the clip WAS transcribed and held nothing: that
 * one sends a designer to the microphone and the room, and this one must not, because the room was
 * never the problem. The chain's own FAILED message ends "The provider's reply is in the server log"
 * or "All transcription providers failed" (`backend/app/services/ai.py`) — true sentences written for
 * whoever runs the server, and the reason this one names them rather than pasting their words onto a
 * phone screen in a courtyard where no log can be read.
 */
const val DW_DICTATION_TRANSCRIPTION_FAILED: String =
    "The server took the recording and could not write it down. Dictate it again, or type the answer " +
        "in — nothing is queued, so it will not arrive later. If it keeps happening, tell whoever " +
        "runs the server: the reason is in their log and not on this phone."

/**
 * A 503 that was NOT the route's answer about dictation.
 *
 * `ApiClient` names 502, 503 and 504 as the codes CloudFront returns when this origin is slow or
 * briefly unwell, and the dictation POST is not on its safely-retriable list — that list is every GET
 * plus four named upload-setup paths, so moving the clip onto the workshop-scoped URL did not quietly
 * put it on there — so such a response arrives here
 * unretried and indistinguishable BY CODE from the route's own "no transcription provider
 * configured". They are told apart by the body instead ([WorkshopRepository.designWorkshopDictate]),
 * and this is what the gateway one says: nothing about configuration, nothing to ask an administrator
 * for, and — crucially — no run-scoped writing-off of rung 2 for a server that is merely busy.
 */
const val DW_DICTATION_SERVER_UNREACHABLE: String =
    "The server could not be reached to write this recording down. Nothing was saved. Try again in a " +
        "moment, or type the answer in."

/**
 * WHICH SENTENCE A 200 THAT CARRIED NO TEXT DESERVES.
 *
 * Pure, and here rather than in the composable, because the rule it enforces is one this repository
 * has already got wrong once in the opposite direction and the only way to hold it is a test.
 *
 * THE PROMISE THAT MUST NEVER REACH A DESIGNER. [DW_DICTATION_BUSY] exists because the chain's
 * throttle message ends "…will retry automatically", which is true of the transcription QUEUE and
 * false of this endpoint — it is synchronous and stores nothing, so nothing retries. Reading only the
 * STATUS for that is not enough, and this is the part the first version of this file missed: when a
 * throttled provider is mixed with a hard failure, `_transcribe_sync` appends the throttle's message
 * to the error list and resolves the whole call to FAILED (`ai.py`: `errors.append(str(rate_limited
 * .get("message")))`). The phrase therefore arrives on a FAILED status, and a client that passed
 * `message` through verbatim printed "will retry automatically" under a dictation that nothing on
 * either side will ever retry — the designer waits for words that cannot come.
 *
 * So the phrase is matched as well as the status. It is matched on the English the server writes
 * today; if that wording changes the worst outcome is [DW_DICTATION_TRANSCRIPTION_FAILED] instead of
 * [DW_DICTATION_BUSY], which is a slightly wrong next move rather than a false promise.
 */
fun dwDictationServerAnswerSentence(status: String, message: String?): String = when {
    status.equals("RATE_LIMITED", ignoreCase = true) -> DW_DICTATION_BUSY
    message?.contains("will retry automatically", ignoreCase = true) == true -> DW_DICTATION_BUSY
    status.equals("FAILED", ignoreCase = true) -> DW_DICTATION_TRANSCRIPTION_FAILED
    // COMPLETED with an empty string cannot happen (`ai.py` calls it EMPTY when there is no text),
    // and EMPTY is what this arm is for: the clip was transcribed and held no words.
    else -> DW_DICTATION_NO_WORDS
}
