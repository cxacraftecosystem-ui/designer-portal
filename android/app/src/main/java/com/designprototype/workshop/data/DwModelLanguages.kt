package com.designprototype.workshop.data

/**
 * **WHICH OF THIS APP'S NINETEEN DICTATION LANGUAGES THIS PHONE CAN ACTUALLY HEAR WITH NO SIGNAL —
 * COUNTING BOTH ANDROID'S OWN PACKS AND THIS APP'S OWN MODELS, AND SAYING WHICH IS WHICH.**
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * In the user's own words: *"Odia is not the only language that we are going to configure."*
 *
 * Until now "offline dictation" had ONE answer per language, because there was one source of it:
 * `dwPackState` asked Google's recogniser what it had, and the answer for seventeen of the nineteen
 * on the fleet's own handset was no (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md, raw logcat —
 * thirty languages listed, exactly `hi-IN` and `en-IN` ours). Once this app carries models of its
 * own there are TWO sources, **and they do not serve the same languages.** A model that hears Odia
 * and a Google pack that does not are both "offline dictation", and a screen that showed one number
 * per language would have to pick which of the two it was lying about.
 *
 * **A DESIGNER IN ODISHA CHOOSING A MODEL THAT CANNOT HEAR ODIA IS THE FAILURE THE WHOLE FEATURE
 * EXISTS TO PREVENT**, and it is not hypothetical. The measured position, which is narrower and more
 * useful than the one an earlier draft of this paragraph asserted:
 *
 *  * **Whisper's ninety-nine languages exclude Odia** (docs/DEVICE-TIER-MEASUREMENT.md, the table
 *    under *why it is not the one the plan named*), so the family a reader is most likely to reach
 *    for is not the answer.
 *  * **The `k2-fsa/sherpa-onnx` `asr-models` release carries no INDIC model** — 498 assets read out
 *    of the GitHub API, the only `nemo-ctc` exports English, French, German, Chinese, Russian and
 *    Persian — and AI4Bharat's Odia IndicConformer ships only as a `.nemo` training checkpoint that
 *    sherpa-onnx cannot open. **BOTH HALVES OF THAT ARE STILL TRUE AND THE CONCLUSION DRAWN FROM THEM
 *    WAS NOT: corrected 2026-08-13.** They describe the *old per-language* repos. AI4Bharat also
 *    publish `ai4bharat/indic-conformer-600m-multilingual` — **ONNX, MIT, all 22 scheduled
 *    languages** — which loads on the sherpa-onnx already vendored here and decodes. **Odia is
 *    block 14** of one shared 5633-class vocabulary, at **CER 5.1% / WER 16.7%** on FLEURS.
 *    **AND IT STILL CANNOT BE OFFERED, FOR TWO MEASURED REASONS, WHICH IS WHY THIS ROW DID NOT MOVE:**
 *    its fp32 weights are 2,428,824,576 bytes against this handset's `MemAvailable` of ~1.1–1.3 GB, and
 *    dynamic int8 — measured later the same day, twice — produces a model that decodes the empty string
 *    (654,790,526 bytes) or a single character (883,021,360 bytes). So *"there is an Odia-capable model
 *    and this app cannot run it"* is the true sentence, and it is a different one from either
 *    *"none exists"* or *"one is coming"*. See `docs/ASR-RUNTIME-MEASUREMENT.md`.
 *  * **AND YET AN ODIA-CAPABLE MODEL EXISTS, HAS BEEN RUN ON THE FLEET'S OWN HANDSET, AND IS STILL
 *    NOT OFFERED.** `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` is pinned
 *    by digest in `DwAsrModel.kt`, and `DwAsrEngineProbeTest` transcribed real Odia speech with it on
 *    the SM-M325F: **53.3% WER** on studio read speech, at **~1.24 GB peak RSS**. More than half the
 *    words wrong, and a resident set that [dwPlanFits] refuses on the very handset it was measured on
 *    — against that phone's own ~1.5 GB of free memory it clears neither the suggestion's gate nor
 *    the 512 MiB margin, so [dwModelFit] would call it TIGHT at best. So ~~[DW_TIER1_CATALOGUE] is
 *    empty because of a MEASUREMENT, not because of an absence.~~ **THAT LAST SENTENCE IS FALSE AND IS
 *    CORRECTED 2026-08-13: [DW_TIER1_CATALOGUE] IS NOT EMPTY.** It holds exactly one row — this same
 *    artifact — whose `languages` is `["hi-IN"]`, and `DwDeviceTier.kt`'s own comment above that row
 *    carries the reasoning that filled it: *53.3% Odia is a fact about ODIA and not about the
 *    artifact*, and TIGHT is not WILL_NOT_FIT, so the designer is told the cost and allowed to choose.
 *    **The mechanism of this error is worth more than the correction**: the row was added in
 *    `DwDeviceTier.kt` while the sentence describing its absence lived here, and nothing failed —
 *    no test reads a docstring, so every claim one file makes about another file's CONTENTS decays
 *    silently. What has NOT changed, and is this paragraph's actual point, is that **Odia is still not
 *    offered**: the row exists, `or-IN` is deliberately absent from its `languages`, and
 *    [dwAppModelCoverage] therefore still answers [DwPackState.NO_OFFLINE_PACK] for Odia. Eleven of
 *    the nineteen have now been scored on that handset (`DwModelPlan.accuracy`) and Hindi is still the
 *    only one offered.
 *
 * **THE DIFFERENCE IS THE WHOLE VALUE OF THIS PARAGRAPH.** An earlier version said "no Odia-capable
 * model was found", which contradicted the measurement document in this same repository and, worse,
 * pointed the next reader at a search that has already been done. What is actually open is a model
 * good enough to put in front of a designer — a different job, with a WER bar and a peak RSS ceiling
 * attached to it. docs/DEVICE-TIER-MEASUREMENT.md's own lesson, recorded the evening it was learnt:
 * *"an absence is a claim, and a claim needs a command beside it."*
 *
 * So per-language state, per source, is the only shape that can tell a designer the truth before they
 * spend the download.
 *
 * ── WHY IT REUSES [DwPackState] RATHER THAN INVENTING A SECOND VOCABULARY ─────────────────────
 *
 * The three-state honesty a language needs here — served-and-installed, served-but-not-installed,
 * not-served-by-anything-installed — is the same fact `DwPackState` already carries for Google's
 * packs, and it already has the harder fourth case right: [DwPackState.UNKNOWN] means "we were not
 * able to ask", never "no". Spelling our side with a second enum would put two vocabularies behind
 * one fact and force every surface to translate between them; worse, the translation would be the
 * place the honest-unknown rule got dropped, because a translator has to choose a value and "we did
 * not ask" is the one nobody remembers to carry across.
 *
 * So OUR side answers in [DwPackState]'s words too, using four of its seven:
 *
 *  | our value | what it means about this app's own models |
 *  |---|---|
 *  | [DwPackState.INSTALLED] | a model on this phone, verified in this run, was measured to hear it |
 *  | [DwPackState.DOWNLOADING] | a model that hears it is being fetched now |
 *  | [DwPackState.DOWNLOADABLE] | a measured model hears it and this handset can take it |
 *  | [DwPackState.NO_OFFLINE_PACK] | nothing installed hears it: either no measured model does, or the one that does will not fit this phone |
 *  | [DwPackState.UNKNOWN] | a model hears it and **this app could not read its own files** to say whether it is installed |
 *
 * [DwPackState.NETWORK_ONLY] and [DwPackState.UNSUPPORTED] are never ours: both are claims about a
 * remote recogniser, which is not a thing this app's own models have. They arrive only from the
 * platform side and survive the composition below.
 *
 * ── AND THE UNMEASURED MODEL, WHICH IS THE HONEST-UNKNOWN RULE ONE LEVEL DOWN ─────────────────
 *
 * [DwModelPlan.languages] may be `null`, meaning nobody has checked which languages that artifact
 * hears. Such a model **contributes nothing to any language's state** — it cannot make a row green,
 * because an unchecked model is not evidence — and the fact that it exists is carried separately in
 * [DwLanguageCoverage.someCoverageUnmeasured] so a screen can say so rather than quietly counting
 * it as a no. That is the difference between "this model does not hear Odia" and "nobody has
 * checked whether this model hears Odia", and this repository has already shipped the cost of
 * collapsing those two once.
 *
 * ── WHY THE DECISION IS PURE ──────────────────────────────────────────────────────────────────
 *
 * Plain Kotlin over plain strings. The nineteen themselves live in a Compose file
 * (`DW_DICTATION_LANGUAGES` in `ui/designworkshop/DwDictation.kt`), so every function here takes the
 * tags — and, where it prints them, the labels — as arguments. That is the shape `dwPackStates`
 * already has, and it is what lets `DwModelLanguagesTest` run every line on a desktop JVM.
 */

// ---------------------------------------------------------------------------------------------
// Does one model hear one language?
// ---------------------------------------------------------------------------------------------

/**
 * Whether [plan] was measured to hear [tag]. **Null languages are a no, and not a silent one.**
 *
 * The comparison is [dwTagCovers], shared with the pack list rather than copied: a model that says
 * it serves `or` serves `or-IN`, and a model that says `en-US` does NOT serve `en-IN`. That
 * asymmetry is argued at length where the function lives, and having one copy of it is the reason
 * this file imports rather than reimplements.
 *
 * A `null` language list returns false **everywhere**, which is the fail-closed direction: an
 * unchecked model must never make a language row claim coverage. Callers that need to distinguish
 * "measured: does not serve it" from "unmeasured" read [DwModelPlan.languages] directly — see
 * [DwLanguageCoverage.someCoverageUnmeasured], which is the only place in this app that does.
 */
fun dwModelServesLanguage(plan: DwModelPlan, tag: String): Boolean = plan.servesLanguage(tag)

// ---------------------------------------------------------------------------------------------
// One language, from both sources, and the composition of the two
// ---------------------------------------------------------------------------------------------

/**
 * What this phone can do with one dictation language, counting both sources separately.
 *
 * Both halves are kept beside the composed answer rather than being folded into it, because a
 * designer's next move depends on WHICH source is missing: a Google pack is fetched from the
 * language card, a model of ours from the model list, and "neither, and the server has it" is not
 * an action at all.
 */
data class DwLanguageCoverage(
    val tag: String,

    /** What ANDROID's own packs say. Exactly [dwPackState] — this file does not second-guess it. */
    val fromPlatform: DwPackState,

    /** What THIS APP's own models say. See the file header for the four values it can take. */
    val fromThisApp: DwPackState,

    /**
     * **THE ONE QUESTION A DESIGNER IS ACTUALLY ASKING: can this phone hear this language with no
     * signal, and if not, is there something that would make it?**
     *
     * Composed by [dwOfflineCoverage] from the two above. Read it with the two beside it: the
     * composed value says what is true of the phone, the halves say whose it is.
     */
    val offline: DwPackState,

    /** Ids of this app's models, installed on this phone right now, that were measured to hear it. */
    val servedByInstalled: List<String>,

    /** Ids of models that hear it and that this handset could install today. */
    val servedByInstallable: List<String>,

    /**
     * Ids of models that hear it and **cannot be installed on this handset** — too large, wrong
     * processor, or already measured failing to load here.
     *
     * Listed rather than dropped because it is the one case where the honest answer to "why can I
     * not have Odia" is "a model for it exists and your phone cannot run it", which is a different
     * and much more useful thing to be told than "no model hears Odia".
     */
    val servedByModelsThatWillNotFit: List<String>,

    /**
     * **TRUE WHEN SOME MODEL'S LANGUAGE COVERAGE HAS NEVER BEEN CHECKED.**
     *
     * Not a state, a caveat: it says this row's answer was computed without knowing what one of the
     * catalogue's models can hear. It is deliberately NOT allowed to change [fromThisApp], because
     * an unmeasured model is not evidence in either direction — it is the reason the row's sentence
     * carries a clause rather than the reason it says yes.
     */
    val someCoverageUnmeasured: Boolean,
)

/**
 * Rank the pack states so two answers about one language can be composed. **Lower is more useful.**
 *
 * NOT AN ORDERING OF GOODNESS AND NOT PUBLIC, because it is only meaningful for this one job. The
 * two decisions in it that were argued over:
 *
 *  * **[DwPackState.UNKNOWN] outranks both "no" states.** A phone that could not be asked has not
 *    said no. Ranking it below them would let a language that may well work be rendered as one that
 *    cannot, which is the exact defect `DwPackState.NO_OFFLINE_PACK` was introduced to fix — it was
 *    doing it to seventeen rows on every settings screen.
 *  * **[DwPackState.UNSUPPORTED] outranks [DwPackState.NO_OFFLINE_PACK].** Both are a no, so the
 *    one that survives is the one that was MEASURED: `UNSUPPORTED` means some engine answered about
 *    online support and this language was absent, while `NO_OFFLINE_PACK` explicitly means the
 *    online question was never asked. Composing them the other way round would take a measured
 *    claim and replace it with "nobody asked", which is a loss of information dressed up as
 *    caution.
 */
private fun dwCoverageRank(state: DwPackState): Int = when (state) {
    DwPackState.INSTALLED -> 0
    DwPackState.DOWNLOADING -> 1
    DwPackState.DOWNLOADABLE -> 2
    DwPackState.NETWORK_ONLY -> 3
    DwPackState.UNKNOWN -> 4
    DwPackState.UNSUPPORTED -> 5
    DwPackState.NO_OFFLINE_PACK -> 6
}

/**
 * The two sources, composed into the one answer about this phone. **Neither source can be lost.**
 *
 * The useful half of the composition is the pair that used to be impossible: Android's packs say
 * `NO_OFFLINE_PACK` for Odia on the fleet's handset (measured), and once a model of ours hears it
 * the composed answer becomes [DwPackState.INSTALLED] or [DwPackState.DOWNLOADABLE] — the row goes
 * from "needs a connection" to "works in a courtyard" without anybody editing a sentence.
 *
 * The half that must not break: with no model of ours involved, the composed answer is **exactly**
 * the platform's, on every one of the seven states. `DwModelLanguagesTest` pins that, because it is
 * what makes this file safe to put underneath a card that has been telling the truth about Google's
 * packs since before it existed.
 */
fun dwOfflineCoverage(fromPlatform: DwPackState, fromThisApp: DwPackState): DwPackState =
    if (dwCoverageRank(fromThisApp) < dwCoverageRank(fromPlatform)) fromThisApp else fromPlatform

/**
 * What THIS APP's own models can do with one language, in [DwPackState]'s words.
 *
 * @param choices every measured model for the tier, already judged against this handset by
 *   [dwModelChoices] — so this function never re-decides whether something fits, it only reads the
 *   fit somebody else computed from the same reading.
 * @param installedModelIds ids verified on this phone **in this run**. Empty today: no model can be
 *   installed at all until the lane wiring the engine lands one, and this app does not keep a note
 *   about installed models that outlives the run — the same rule `DwAsrRuntimeStatus` keeps for the
 *   engine, and for the same reason.
 * @param installingModelIds ids being fetched right now. A separate state because asking again
 *   achieves nothing and a second download button beside a file already coming is how somebody pays
 *   twice on a prepaid bundle — [DwPackState.DOWNLOADING]'s own argument, one file over.
 * @param installKnown false when the app could not read its own storage. Then a language a model
 *   serves is [DwPackState.UNKNOWN] rather than "not installed", because claiming "not installed"
 *   would offer a designer a download they may have already paid for.
 */
fun dwAppModelCoverage(
    tag: String,
    choices: List<DwModelChoice>,
    installedModelIds: Set<String> = emptySet(),
    installingModelIds: Set<String> = emptySet(),
    installKnown: Boolean = true,
): DwPackState {
    val serving = choices.filter { dwModelServesLanguage(it.plan, tag) }
    /*
     * NOTHING MEASURED HEARS IT, so the install state cannot matter and is not consulted. That is
     * the answer for Odia today — not because no Odia-capable model exists (one has been run on the
     * fleet's own handset; see this file's header) but because none is in [DW_TIER1_CATALOGUE],
     * which is the only list [choices] can be built from. It is reached without asking a question
     * about storage that could only ever return an irrelevant "unknown" — the same ordering
     * `dwAsrOffer` got wrong once and fixed, where an empty catalogue was being reported as "this
     * app could not look at its own files".
     */
    if (serving.isEmpty()) return DwPackState.NO_OFFLINE_PACK
    // A model hears it and we cannot say whether it is here. Never "not installed".
    if (!installKnown) return DwPackState.UNKNOWN
    if (serving.any { it.plan.modelId in installedModelIds && !dwRefusedByThisHandset(it) }) {
        return DwPackState.INSTALLED
    }
    if (serving.any { it.plan.modelId in installingModelIds }) return DwPackState.DOWNLOADING
    if (serving.any { it.fit.mayInstall }) return DwPackState.DOWNLOADABLE
    // A model hears it and this handset cannot have it, or has already proved it cannot run it.
    // Still no offline pack — the WHY is carried in
    // [DwLanguageCoverage.servedByModelsThatWillNotFit], where a sentence can reach it.
    return DwPackState.NO_OFFLINE_PACK
}

/**
 * **THIS HANDSET HAS ALREADY TRIED THIS MODEL AND IT DID NOT LOAD.** Installed is not the same as
 * working, and this is the one difference between them that has been measured.
 *
 * ── THE FAILURE THIS EXISTS TO PREVENT ────────────────────────────────────────────────────────
 *
 * [dwAppModelCoverage] used to answer [DwPackState.INSTALLED] for any serving model whose id was in
 * `installedModelIds`, whatever [dwModelFit] had just concluded about it. So a model whose bytes are
 * on the phone and which has ALREADY FAILED TO LOAD here — [DwFitNote.LOAD_FAILED_HERE_BEFORE],
 * which `dwModelFit` treats as outranking every piece of arithmetic and which it says "will not be
 * tried again here" — would still turn its language's row green and print *"Dictation in Odia works
 * with no signal"*. The same boolean feeds `DwDictationConditions.appModelServesLanguage`, so the
 * ladder would put [DwDictationRung.APP_SPEECH_MODEL] first for a model the app has already retired,
 * and the designer would find out after they had spoken. Two screens, two accounts of one fact.
 *
 * ── WHY IT IS THIS NOTE AND NOT `!fit.mayInstall` ─────────────────────────────────────────────
 *
 * Because `mayInstall` answers a question about INSTALLING, and an installed model is past it. A
 * model already on the phone can be WILL_NOT_FIT for [DwFitNote.LARGER_THAN_THE_FREE_STORAGE] purely
 * because a workshop day of photographs has since filled the volume — its own bytes are already
 * written, it loads and runs perfectly well, and calling its language uncovered would withdraw a
 * working capability over a download that is not going to happen. The load failure is the only note
 * in the enum that is a measurement OF THIS MODEL ON THIS HANDSET, and it is the only one that
 * survives being installed.
 */
private fun dwRefusedByThisHandset(choice: DwModelChoice): Boolean =
    DwFitNote.LOAD_FAILED_HERE_BEFORE in choice.notes

/**
 * Every language's coverage, in the order given, as one pass over one reading.
 *
 * A `LinkedHashMap` for `dwPackStates`' reason: the nineteen have a deliberate order (Hindi first,
 * matching the web form's default) and a settings list that reordered itself by state would move
 * the row a designer is reaching for as they reach for it.
 *
 * ── **NOTHING IN THE APP DRAWS THIS TODAY, AND THAT IS RECORDED RATHER THAN HIDDEN.** ─────────
 *
 * Its one consumer was `DwLanguageCoverageList`, deleted 2026-08-13 along with the three sentence
 * functions it drew. So this is composition arithmetic with no UI caller, kept on a narrow argument
 * that has to be stated or it will read as an oversight:
 *
 *  * **It is arithmetic, not copy.** The re-hang hazard the deletion was about was 1,207 words of
 *    finished prose sitting next to a finished list. What is left composes two `DwPackState`s and
 *    returns data; nobody puts data on a screen by accident.
 *  * **Its tests are the record of a shipped defect being fixed.** `DwModelLanguagesTest` pins,
 *    through this function, that [DwPackState.UNKNOWN] outranks both kinds of "no" and that
 *    UNSUPPORTED outranks NO_OFFLINE_PACK — the exact rule whose absence rendered seventeen working
 *    languages as unsupported on every settings screen in the fleet. Deleting the function deletes
 *    the only executable statement of that rule.
 *  * **The rule itself is live.** [dwOfflineCoverage] — the ranking this builds on — is what
 *    [dwSpeechSummaryLine] composes the settings row's count with, so the ranking has a caller on a
 *    screen a designer sees even though this convenience wrapper does not.
 *
 * **IF YOU ARE ABOUT TO DRAW THIS AGAIN**: the fields are honest and the composition is right, and
 * neither is the reason the last surface was deleted. Answer principle 3 first — a row about a
 * language nobody can install, download or manage does not belong in a list, whatever its sentence
 * says — and put what a designer needs where they hit it, which is the dictation flow.
 */
fun dwLanguageCoverages(
    tags: List<String>,
    support: DwRecognitionSupport?,
    choices: List<DwModelChoice>,
    installedModelIds: Set<String> = emptySet(),
    installingModelIds: Set<String> = emptySet(),
    installKnown: Boolean = true,
): Map<String, DwLanguageCoverage> {
    val out = LinkedHashMap<String, DwLanguageCoverage>(tags.size)
    tags.forEach { tag ->
        val serving = choices.filter { dwModelServesLanguage(it.plan, tag) }
        val platform = dwPackState(tag, support)
        val ours = dwAppModelCoverage(tag, choices, installedModelIds, installingModelIds, installKnown)
        out[tag] = DwLanguageCoverage(
            tag = tag,
            fromPlatform = platform,
            fromThisApp = ours,
            offline = dwOfflineCoverage(platform, ours),
            // THE SAME PREDICATE [dwAppModelCoverage] ANSWERS INSTALLED FROM, so the list and the
            // state cannot disagree: a model this handset has already refused to load is not
            // "serving" anything here, and naming it in the green sentence would have the row cite
            // its own evidence against itself. It stays in [servedByModelsThatWillNotFit] below,
            // where the sentence that names it says the true thing.
            servedByInstalled = serving
                .filter { it.plan.modelId in installedModelIds && !dwRefusedByThisHandset(it) }
                .map { it.plan.modelId },
            servedByInstallable = serving
                .filter { it.fit.mayInstall && it.plan.modelId !in installedModelIds }
                .map { it.plan.modelId },
            servedByModelsThatWillNotFit = serving.filterNot { it.fit.mayInstall }.map { it.plan.modelId },
            // Any model whose languages were never checked leaves this row's answer incomplete,
            // whether or not anything else covers it.
            someCoverageUnmeasured = choices.any { it.plan.languages == null },
        )
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// The words
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// `dwCoverageLabel` AND `dwCoverageSentence` STOOD HERE, 84 lines of them. Both are deleted.
//
// `dwCoverageLabel` was one line — `dwPackStateLabel(coverage.offline)` — with a nine-line docstring
// arguing, correctly, that a row served by a Google pack and a row served by one of our models must
// read the same. That argument is still true and is still enforced, because the only label function
// left IS `dwPackStateLabel`; the alias had no caller.
//
// `dwCoverageSentence` was the nine-armed paragraph machine, and it is the 1,207 words. One arm per
// composed state, each naming the language and the source, and its last arm — the one every Indian
// language except Hindi reaches on every handset in the fleet — ran to 55 words explaining that
// Android has no pack, no model of ours hears it, the server does, and to type the answer in.
//
// WHY IT WENT: `DwLanguageCoverageList` drew it and nothing else did. The owner, three times: *"I do
// not need to know it about each and every language in three paragraphs whether it has been
// downloaded or not."*
//
// THE ONE FACT IN IT THAT A DESIGNER GENUINELY NEEDS — that this language will not work in a
// courtyard with no bars — IS NOT LOST, and it must not be. It is said in the dictation flow at the
// moment it costs something, by `dwDictationNothingLeftSentence`, which is reached when the ladder
// runs out of rungs. That is the placement principle 2 requires (report a failure AT THE MOMENT IT
// FAILS, never as a standing disclaimer) and it is where the web says it too. If you are here
// intending to restore a per-language sentence to a settings list, that is the function to read
// first, and principle 3 is the one to answer.
// ---------------------------------------------------------------------------------------------

/**
 * **THE WHOLE SPEECH SURFACE, IN ONE CLAUSE, ON A ROW A DESIGNER HAS NOT TAPPED YET.**
 *
 * ── WHY A ROW NEEDS A SUMMARY AT ALL ──────────────────────────────────────────────────────────
 *
 * The brief: *"ONE row into a 'Speech & AI' sub-screen **carrying a short true state summary**"*. A
 * settings row that says only "Speech & AI ›" makes a designer open a screen to find out whether
 * they need to open it. The one question behind all four of the cards that used to be here is *can
 * this phone write words down in a courtyard with no bars*, and it has a number for an answer.
 *
 * ── IT COMPOSES BOTH SOURCES, CHEAPLY, AND CANNOT DOUBLE-COUNT ────────────────────────────────
 *
 * A language works offline if Android's own pack covers it **or** a verified model of ours does —
 * the union, exactly as [dwOfflineCoverage] ranks it, but computed from two sets rather than by
 * building nineteen [DwLanguageCoverage] objects. That matters because this line is drawn on the
 * Appearance screen, and the full composition needs a device probe and a fit calculation that screen
 * has no other reason to pay for.
 *
 * ── AND IT NEVER GUESSES ──────────────────────────────────────────────────────────────────────
 *
 * [canAsk] false (API < 33) does not print "0 languages work offline". Zero is a measurement and this
 * is the absence of one; printing the number would tell an Android 12 designer their phone cannot
 * dictate offline when it may well have Hindi installed. It says so instead.
 *
 * @param modelServedTags tags served by a model **installed and verified in this run**. Empty when
 *   there is none, which is the fail-closed direction: an unverified model must not turn a row green.
 */
fun dwSpeechSummaryLine(
    packStates: Map<String, DwPackState>,
    modelState: DwAsrModelState,
    modelServedTags: Set<String> = emptySet(),
    canAsk: Boolean = true,
): String {
    val modelClause = when (modelState) {
        DwAsrModelState.INSTALLED -> " · speech model installed"
        DwAsrModelState.INSTALLING -> " · speech model installing"
        DwAsrModelState.PAUSED -> " · speech model download paused"
        // NOT_INSTALLED, FAILED and UNKNOWN say nothing. "Speech model not installed" on a row is a
        // fact about a thing the designer has never heard of and has not asked for — the narration
        // principle 1 forbids. The card behind the row says it, where it comes with a size and a
        // button.
        else -> ""
    }
    if (!canAsk) return "This Android version cannot be asked which packs it has$modelClause"
    val total = packStates.size
    if (total == 0) return "No dictation languages configured"
    /*
     * THE UNION IS ASKED OF [dwOfflineCoverage] RATHER THAN RE-WRITTEN HERE.
     *
     * This line used to read `state == INSTALLED || tag in modelServedTags`, which is the right answer
     * arrived at by a second route. Two implementations of one rule is how the settings row and the
     * settings card come to two accounts of one phone — the failure this file's own header spends a
     * paragraph on. Composing through the ranking function costs one `when` per language and means
     * that when the ranking changes, this count changes with it.
     *
     * A tag a verified model serves is handed in as INSTALLED, and a tag it does not as
     * NO_OFFLINE_PACK — the measured "no", which is what an installed model that was measured against
     * these languages and does not hear this one actually establishes. It is also the value ranked
     * below UNKNOWN, so a model of ours can never turn a platform UNKNOWN into a claim.
     */
    val offline = packStates.count { (tag, state) ->
        val ours = if (tag in modelServedTags) DwPackState.INSTALLED else DwPackState.NO_OFFLINE_PACK
        dwOfflineCoverage(state, ours) == DwPackState.INSTALLED
    }
    val head = when (offline) {
        0 -> "No languages work offline"
        1 -> "1 of $total languages works offline"
        else -> "$offline of $total languages work offline"
    }
    return head + modelClause
}

// ---------------------------------------------------------------------------------------------
// `dwCoverageSummarySentence` STOOD HERE, 72 lines of it, and it is deleted with the list it opened.
//
// WHAT IT SAID: one paragraph counting the nineteen — how many work with no signal, how many more
// could once a model is installed, how many more once a Google pack is, how many could not be
// established, and then it NAMED up to three of the languages that need a connection and counted the
// rest. On the fleet's SM-M325F that is a single sentence roughly 60 words long, at the top of a list
// of seventeen further paragraphs, all of it about things a designer cannot act on from that screen.
//
// WHY IT WENT: it was drawn by `DwLanguageCoverageList` and by nothing else, and that composable is
// deleted (see the block at the foot of `ui/designworkshop/DwModelChoiceUi.kt`). Keeping the copy
// after deleting the only surface that drew it would leave the next reader a finished paragraph and
// an obvious place to hang it.
//
// WHAT REPLACED IT, AND IT IS NOT NOTHING: [dwSpeechSummaryLine], immediately above. Same question —
// how many of these languages work with no signal — answered in one clause on the settings ROW, from
// two sets rather than nineteen objects, so it costs no device probe. It is what the Appearance
// screen draws today. A count against a total survived; the paragraph did not.
// ---------------------------------------------------------------------------------------------
