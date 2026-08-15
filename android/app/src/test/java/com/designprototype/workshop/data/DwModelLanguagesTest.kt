package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PER-LANGUAGE COVERAGE, FROM BOTH SOURCES, ON THE DESKTOP JVM.
 *
 * ── WHAT THIS FILE IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * *"Odia is not the only language that we are going to configure."* Once this app carries speech
 * models of its own there are two offline sources on one handset and **they do not serve the same
 * languages**. The failure this file exists to make impossible is a designer in Odisha reading a
 * row that says a language works offline when nothing on the phone can hear it — or, just as bad,
 * one that says it cannot when a model they installed does.
 *
 * ── THE TWO INVARIANTS THAT MATTER MOST ───────────────────────────────────────────────────────
 *
 *  * **With no model of ours involved, the composed answer is EXACTLY the platform's**, on all
 *    seven states. That is what makes this safe to put under a card which has been telling the
 *    truth about Google's packs since before it existed.
 *  * **An unmeasured model changes nothing.** A model whose language list is null cannot make a row
 *    claim coverage, because an unchecked model is not evidence — the honest-unknown rule, one
 *    level down from where `DwPackState.UNKNOWN` applies it.
 */
class DwModelLanguagesTest {

    private val mib: Long = 1024L * 1024L

    private val ordinaryPhone = DwDeviceMeasurement(
        totalRamBytes = 3_700L * mib,
        availableRamBytes = 1_100L * mib,
        lowRamDevice = false,
        freeStorageBytes = 12_000L * mib,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
    )

    /** A model that hears Odia and Assamese, and nothing else. Invented, like everything here. */
    private val NOT_A_REAL_ODIA_MODEL = DwModelPlan(
        modelId = "not-a-real-odia-model",
        quantisation = "int8",
        abi = "arm64-v8a",
        languages = listOf("or-IN", "as-IN"),
        contextCapTokens = 1024,
        // A DECODER-SHAPED FIXTURE, so the cap above is the envelope and this sentence
        // restates it. Nothing here was measured; see this file's fixture rule.
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 150L * mib,
        peakRssBytes = 200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    /** Same model, too large for any handset in this file. */
    private val NOT_A_REAL_ENORMOUS_ODIA_MODEL = NOT_A_REAL_ODIA_MODEL.copy(
        modelId = "not-a-real-enormous-odia-model",
        peakRssBytes = 9_000L * mib,
    )

    /** A model nobody has checked against the nineteen. Null is the word "unmeasured". */
    private val NOT_A_REAL_UNCHECKED_MODEL = NOT_A_REAL_ODIA_MODEL.copy(
        modelId = "not-a-real-unchecked-model",
        languages = null,
    )

    private val odia = "or-IN"
    private val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
    private val tags = DW_DICTATION_LANGUAGES.map { it.tag }

    /**
     * WHAT THE FLEET'S HANDSET ACTUALLY REPORTED, IN SHAPE ONLY. Thirty on-device languages of which
     * two are ours (docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md is the raw logcat); the online list
     * is empty because an on-device recogniser returns one by construction.
     *
     * The two tags are the measured ones. The fixture is not named after the handset, deliberately:
     * `DwLanguagePackTest`'s scar is a fixture named for a phone asserting what it did not do.
     */
    private val platformWithTwoOfOurs = DwRecognitionSupport(
        installedOnDevice = listOf("hi-IN", "en-IN"),
    )

    private fun choicesOf(vararg plans: DwModelPlan) =
        dwModelChoices(plans.toList(), ordinaryPhone, emptyList(), null, DwAiTier.TIER_1)

    // ---------------------------------------------------------------------------------------
    // The invariant that protects the card that already worked
    // ---------------------------------------------------------------------------------------

    @Test
    fun `with no models of our own, every language reads exactly as the pack list says`() {
        val coverages = dwLanguageCoverages(tags, platformWithTwoOfOurs, choices = emptyList())
        val packStates = dwPackStates(tags, platformWithTwoOfOurs)
        tags.forEach { tag ->
            assertEquals(
                "$tag: composing an empty model catalogue changed the platform's own answer",
                packStates[tag],
                coverages[tag]?.offline
            )
        }
        // And that is today's answer on every handset in the fleet: two of the nineteen.
        assertEquals(2, coverages.values.count { it.offline == DwPackState.INSTALLED })
    }

    @Test
    fun `the composition never loses a platform state, on any of the seven`() {
        DwPackState.entries.forEach { platform ->
            assertEquals(
                "with nothing of ours to say, the composed answer is the platform's",
                platform,
                dwOfflineCoverage(platform, DwPackState.NO_OFFLINE_PACK)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // ODIA — the row this whole feature is judged on
    // ---------------------------------------------------------------------------------------

    @Test
    fun `today nothing serves Odia, from either source, and neither half is lost`() {
        val coverage = dwLanguageCoverages(tags, platformWithTwoOfOurs, choices = emptyList())[odia]!!
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromPlatform)
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromThisApp)
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.offline)
        assertTrue(coverage.servedByInstalled.isEmpty())
        assertTrue(coverage.servedByInstallable.isEmpty())
        /*
         * THE SENTENCE ASSERTIONS THAT USED TO CLOSE THIS TEST ARE GONE WITH THE SENTENCE.
         *
         * They pinned `dwCoverageSentence`, deleted 2026-08-13 with the nineteen-row list that was its
         * only caller. What they were guarding — that a language with no offline pack is NOT told to
         * "pick another language", because the server transcribes it perfectly well — is still pinned,
         * on the sentence that IS still drawn: `DwLanguagePackTest` asserts it of
         * `dwPackStateSentence(NO_OFFLINE_PACK)`. The state assertions above are the half of this test
         * that was about the composition rather than about the copy, and they stay.
         */
    }

    @Test
    fun `an installed model that hears Odia turns the row green and names the model`() {
        val choices = choicesOf(NOT_A_REAL_ODIA_MODEL)
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choices,
            installedModelIds = setOf(NOT_A_REAL_ODIA_MODEL.modelId),
        )[odia]!!
        assertEquals(DwPackState.INSTALLED, coverage.fromThisApp)
        assertEquals(DwPackState.INSTALLED, coverage.offline)
        assertEquals(listOf(NOT_A_REAL_ODIA_MODEL.modelId), coverage.servedByInstalled)
        /*
         * THE ROW READS GREEN THROUGH THE PACK VOCABULARY AND NOT A SECOND SET OF LABELS — the point
         * the deleted `dwCoverageLabel` existed to make, and it is still enforced, because composing
         * into `DwPackState` is what leaves `dwPackStateLabel` as the only label function there is.
         */
        assertEquals("Works offline", dwPackStateLabel(coverage.offline))
    }

    @Test
    fun `a model that hears Odia and could be installed makes the row downloadable, not installed`() {
        val coverage = dwLanguageCoverages(
            tags, platformWithTwoOfOurs, choices = choicesOf(NOT_A_REAL_ODIA_MODEL)
        )[odia]!!
        assertEquals(DwPackState.DOWNLOADABLE, coverage.fromThisApp)
        assertEquals(listOf(NOT_A_REAL_ODIA_MODEL.modelId), coverage.servedByInstallable)
        // DOWNLOADABLE AND NOT INSTALLED is the whole assertion: a model that COULD be fetched has not
        // been. Nothing in this app auto-downloads, and `dwModelDownloadMayBeOffered` is the gate that
        // pins that half — `DwModelChoiceTest` holds it.
        assertEquals(DwPackState.DOWNLOADABLE, coverage.offline)
    }

    @Test
    fun `a model that hears Odia and will not fit says exactly that, which is the useful no`() {
        val coverage = dwLanguageCoverages(
            tags, platformWithTwoOfOurs, choices = choicesOf(NOT_A_REAL_ENORMOUS_ODIA_MODEL)
        )[odia]!!
        assertEquals(
            "a model that cannot be installed leaves the language with no offline pack",
            DwPackState.NO_OFFLINE_PACK,
            coverage.fromThisApp
        )
        assertEquals(
            listOf(NOT_A_REAL_ENORMOUS_ODIA_MODEL.modelId),
            coverage.servedByModelsThatWillNotFit
        )
        // THE LIST IS KEPT SEPARATE FROM THE STATE, which is the useful part: "a model for it exists
        // and your phone cannot run it" is a different and better answer than "no model hears Odia",
        // and a caller that wants to say so still has both halves to say it from.
        assertTrue(coverage.servedByInstalled.isEmpty())
        assertTrue(coverage.servedByInstallable.isEmpty())
    }

    @Test
    fun `a model we could not look for is unknown, never not-installed`() {
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choicesOf(NOT_A_REAL_ODIA_MODEL),
            installKnown = false,
        )[odia]!!
        assertEquals(DwPackState.UNKNOWN, coverage.fromThisApp)
        // UNKNOWN AND NOT NO_OFFLINE_PACK once composed: a disk this app could not read has not said
        // no, and `dwCoverageRank` is what keeps the honest unknown above both kinds of no.
        assertEquals(DwPackState.UNKNOWN, coverage.offline)
    }

    @Test
    fun `a language no model hears is not made unknown by an unreadable disk`() {
        // The ordering that `dwAsrOffer` got wrong once and fixed: an empty answer about the
        // catalogue outranks an unread disk, because no reading of storage could change it.
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choicesOf(NOT_A_REAL_ODIA_MODEL),
            installKnown = false,
        )["ta-IN"]!!
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromThisApp)
    }

    // ---------------------------------------------------------------------------------------
    // The unmeasured model
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a model nobody has checked covers nothing, and the row says it was not counted`() {
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choicesOf(NOT_A_REAL_UNCHECKED_MODEL),
            installedModelIds = setOf(NOT_A_REAL_UNCHECKED_MODEL.modelId),
        )[odia]!!
        assertEquals(
            "an installed model whose languages were never checked must not turn a row green",
            DwPackState.NO_OFFLINE_PACK,
            coverage.fromThisApp
        )
        // CARRIED AS A CAVEAT BESIDE THE STATE, never folded into it: an unchecked model is not
        // evidence in either direction, so it may not move `fromThisApp` and it may not be silently
        // dropped either.
        assertTrue(coverage.someCoverageUnmeasured)
    }

    @Test
    fun `a measured model that serves none of the nineteen is not the same fact as an unchecked one`() {
        val servesNone = NOT_A_REAL_ODIA_MODEL.copy(modelId = "not-a-real-english-only", languages = emptyList())
        val coverage = dwLanguageCoverages(
            tags, platformWithTwoOfOurs, choices = choicesOf(servesNone)
        )[odia]!!
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromThisApp)
        assertFalse("this one WAS checked; there is no caveat to print", coverage.someCoverageUnmeasured)
    }

    // ---------------------------------------------------------------------------------------
    // Tag matching — one rule, shared with the pack list
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a model claiming a bare language serves every region of it, and never the other way round`() {
        val bare = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("or"))
        assertTrue("a model that hears “or” hears or-IN", dwModelServesLanguage(bare, "or-IN"))

        // The asymmetry that stops eight of the nineteen being claimed by a US English pack.
        val american = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("en-US"))
        assertFalse(dwModelServesLanguage(american, "en-IN"))

        // Separator and case are not part of the question: `or_IN`, `or-IN` and `OR-in` are one tag.
        val untidy = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("OR_in"))
        assertTrue(dwModelServesLanguage(untidy, "or-IN"))
    }

    @Test
    fun `a model with no measured languages never serves anything`() {
        tags.forEach { tag ->
            assertFalse(dwModelServesLanguage(NOT_A_REAL_UNCHECKED_MODEL, tag))
        }
    }

    // ---------------------------------------------------------------------------------------
    // The composition's own precedence rules
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unknown outranks both kinds of no, because it has not said no`() {
        assertEquals(DwPackState.UNKNOWN, dwOfflineCoverage(DwPackState.UNKNOWN, DwPackState.NO_OFFLINE_PACK))
        assertEquals(DwPackState.UNKNOWN, dwOfflineCoverage(DwPackState.UNSUPPORTED, DwPackState.UNKNOWN))
    }

    @Test
    fun `between two nos the measured one survives`() {
        // UNSUPPORTED means an engine answered about online support and this language was absent;
        // NO_OFFLINE_PACK means the online question was never asked. Replacing the first with the
        // second would lose information and call it caution.
        assertEquals(
            DwPackState.UNSUPPORTED,
            dwOfflineCoverage(DwPackState.UNSUPPORTED, DwPackState.NO_OFFLINE_PACK)
        )
    }

    @Test
    fun `anything installed beats everything, from either source`() {
        assertEquals(
            DwPackState.INSTALLED,
            dwOfflineCoverage(DwPackState.NO_OFFLINE_PACK, DwPackState.INSTALLED)
        )
        assertEquals(
            DwPackState.INSTALLED,
            dwOfflineCoverage(DwPackState.INSTALLED, DwPackState.NO_OFFLINE_PACK)
        )
    }

    // ---------------------------------------------------------------------------------------
    // THE SUMMARY. Four tests stood here against `dwCoverageSummarySentence` and are re-pointed at
    // `dwSpeechSummaryLine`, which is the summary that is actually drawn.
    //
    // `dwCoverageSummarySentence` was deleted 2026-08-13 with the nineteen-row list it opened. It
    // counted the same thing this one counts and then spent forty more words naming three of the
    // languages that need a connection and counting the rest. `dwSpeechSummaryLine` answers the same
    // question — how many of these languages work with no signal — in one clause on the Settings row,
    // from two sets rather than nineteen composed objects, so it needs no device probe to draw.
    //
    // IT HAD ZERO TESTS UNTIL NOW, WHICH IS WHY THESE ARE HERE AND NOT MERELY DELETED. It is on the
    // Appearance screen of every build, above the tap into Speech & AI, and it is the only sentence in
    // this feature a designer reads without having gone looking for it.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the row counts offline languages against a total, and never rounds`() {
        val states = dwPackStates(tags, platformWithTwoOfOurs)
        val line = dwSpeechSummaryLine(states, DwAsrModelState.NOT_INSTALLED)
        // Two of the nineteen, which is what the fleet's handset answered. A count against a total
        // cannot survive being wrong the way "most languages work offline" can.
        assertEquals("2 of 19 languages work offline", line)
    }

    @Test
    fun `one offline language takes the singular verb`() {
        // THE AGREEMENT DEFECT `dwAsrModelWhatItBuysSentence` SHIPPED, asked of this function too.
        // One is a reachable count here — a handset carrying only Hindi — and "1 of 19 languages work
        // offline" would be the same defect on the row above the one that had it.
        val onlyHindi = DwRecognitionSupport(installedOnDevice = listOf("hi-IN"))
        val line = dwSpeechSummaryLine(dwPackStates(tags, onlyHindi), DwAsrModelState.NOT_INSTALLED)
        assertEquals("1 of 19 languages works offline", line)
    }

    @Test
    fun `zero offline languages says so without claiming dictation is broken`() {
        val nothing = DwRecognitionSupport(installedOnDevice = emptyList(), supportedOnDevice = tags)
        val line = dwSpeechSummaryLine(dwPackStates(tags, nothing), DwAsrModelState.NOT_INSTALLED)
        assertEquals("No languages work offline", line)
        // The row must not read as "dictation is unavailable". Every one of the nineteen still
        // dictates through the server, and seventeen of them only ever did.
        assertFalse(line.contains("cannot"))
        assertFalse(line.contains("unavailable"))
    }

    @Test
    fun `a verified model of ours is counted with the packs and never twice`() {
        val states = dwPackStates(tags, platformWithTwoOfOurs)
        // hi-IN is ALREADY installed as a Google pack on this reading, so a model serving hi-IN and
        // or-IN adds exactly one: the union, not the sum. This is the arithmetic
        // `dwAsrModelWhatItBuysSentence` exists to state in words, and the double-count is what
        // `dwOfflineCoverage` is composed through to prevent.
        val line = dwSpeechSummaryLine(
            states,
            DwAsrModelState.INSTALLED,
            modelServedTags = setOf("hi-IN", "or-IN"),
        )
        assertEquals("3 of 19 languages work offline \u00B7 speech model installed", line)
    }

    @Test
    fun `a model of ours cannot turn an unasked platform into a claim`() {
        /*
         * THE RANKING RULE, THROUGH THE FUNCTION THAT NOW COMPOSES WITH IT. `canAsk` false is the
         * Android-12 case: the platform was never asked, so printing "0 languages work offline" would
         * tell a designer their phone cannot dictate offline when it may well have Hindi installed. The
         * model clause still appends, because that half WAS measured.
         */
        val line = dwSpeechSummaryLine(
            dwPackStates(tags, null),
            DwAsrModelState.INSTALLED,
            modelServedTags = setOf("hi-IN"),
            canAsk = false,
        )
        assertEquals(
            "This Android version cannot be asked which packs it has \u00B7 speech model installed",
            line
        )
        // And with no model, no clause: a designer who has never heard of the speech model is not told
        // it is absent.
        assertFalse(
            dwSpeechSummaryLine(dwPackStates(tags, null), DwAsrModelState.NOT_INSTALLED, canAsk = false)
                .contains("speech model")
        )
    }

    @Test
    fun `an empty language list is a configuration fault and says so, not a zero`() {
        assertEquals(
            "No dictation languages configured",
            dwSpeechSummaryLine(emptyMap(), DwAsrModelState.NOT_INSTALLED)
        )
    }

    @Test
    fun `a model this handset has already refused to load does not turn its language green`() {
        /*
         * INSTALLED IS NOT THE SAME AS WORKING, AND THE DIFFERENCE HAS BEEN MEASURED. A model whose
         * bytes are on the phone and which has already failed to load HERE is WILL_NOT_FIT by
         * `dwModelFit`'s first and most durable check, which says in its own words that it "will not
         * be tried again here". A row that then printed "Dictation in Odia works with no signal"
         * would be the card citing the app's own evidence against itself — and the same boolean
         * feeds `DwDictationConditions.appModelServesLanguage`, so the ladder would open a model
         * that has already said no and the designer would find out after they had spoken.
         */
        val failedHere = listOf(
            DwLoadFailureNote(
                tier = DwAiTier.TIER_1,
                modelId = NOT_A_REAL_ODIA_MODEL.modelId,
                contextCapTokens = NOT_A_REAL_ODIA_MODEL.contextCapTokens,
                detail = "invented for this test",
            )
        )
        val choices = dwModelChoices(
            listOf(NOT_A_REAL_ODIA_MODEL), ordinaryPhone, failedHere, null, DwAiTier.TIER_1
        )
        assertEquals(DwModelFit.WILL_NOT_FIT, choices.single().fit)

        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choices,
            installedModelIds = setOf(NOT_A_REAL_ODIA_MODEL.modelId),
        )[odia]!!
        assertEquals(
            "a model this handset has proved it cannot load is not serving anything on it",
            DwPackState.NO_OFFLINE_PACK,
            coverage.fromThisApp
        )
        assertTrue(coverage.servedByInstalled.isEmpty())
        assertEquals(
            listOf(NOT_A_REAL_ODIA_MODEL.modelId),
            coverage.servedByModelsThatWillNotFit
        )
        // AND THE ROW MUST NOT ALSO READ GREEN. The label is derived from the composed state, so a
        // model this handset has proved it cannot load cannot produce "Works offline" anywhere.
        assertEquals("No offline pack", dwPackStateLabel(coverage.offline))
    }

    @Test
    fun `an installed model short of storage for a fresh download still serves its language`() {
        /*
         * THE OTHER DIRECTION OF THE SAME RULE, AND THE REASON THE CHECK ABOVE IS THE LOAD FAILURE
         * RATHER THAN `!fit.mayInstall`. A workshop day of photographs fills the volume, so a model
         * ALREADY ON THE PHONE goes WILL_NOT_FIT on `LARGER_THAN_THE_FREE_STORAGE` — its bytes are
         * written, it loads and runs perfectly well, and withdrawing offline Odia because a download
         * that is not going to happen would no longer fit would be this app taking away a capability
         * a designer had paid for.
         */
        val fullPhone = ordinaryPhone.copy(freeStorageBytes = 10L * mib)
        val choices = dwModelChoices(
            listOf(NOT_A_REAL_ODIA_MODEL), fullPhone, emptyList(), null, DwAiTier.TIER_1
        )
        assertEquals(DwModelFit.WILL_NOT_FIT, choices.single().fit)
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choices,
            installedModelIds = setOf(NOT_A_REAL_ODIA_MODEL.modelId),
        )[odia]!!
        assertEquals(DwPackState.INSTALLED, coverage.fromThisApp)
        assertEquals(listOf(NOT_A_REAL_ODIA_MODEL.modelId), coverage.servedByInstalled)
    }
}
