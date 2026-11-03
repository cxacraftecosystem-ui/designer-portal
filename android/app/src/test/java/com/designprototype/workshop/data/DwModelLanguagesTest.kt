package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwModelLanguagesTest {

    private val mib: Long = 1024L * 1024L

    private val ordinaryPhone = DwDeviceMeasurement(
        totalRamBytes = 3_700L * mib,
        availableRamBytes = 1_100L * mib,
        lowRamDevice = false,
        freeStorageBytes = 12_000L * mib,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
    )

    private val NOT_A_REAL_ODIA_MODEL = DwModelPlan(
        modelId = "not-a-real-odia-model",
        quantisation = "int8",
        abi = "arm64-v8a",
        languages = listOf("or-IN", "as-IN"),
        contextCapTokens = 1024,
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 150L * mib,
        peakRssBytes = 200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    private val NOT_A_REAL_ENORMOUS_ODIA_MODEL = NOT_A_REAL_ODIA_MODEL.copy(
        modelId = "not-a-real-enormous-odia-model",
        peakRssBytes = 9_000L * mib,
    )

    private val NOT_A_REAL_UNCHECKED_MODEL = NOT_A_REAL_ODIA_MODEL.copy(
        modelId = "not-a-real-unchecked-model",
        languages = null,
    )

    private val odia = "or-IN"
    private val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
    private val tags = DW_DICTATION_LANGUAGES.map { it.tag }

    private val platformWithTwoOfOurs = DwRecognitionSupport(
        installedOnDevice = listOf("hi-IN", "en-IN"),
    )

    private fun choicesOf(vararg plans: DwModelPlan) =
        dwModelChoices(plans.toList(), ordinaryPhone, emptyList(), null, DwAiTier.TIER_1)

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

    @Test
    fun `today nothing serves Odia, from either source, and neither half is lost`() {
        val coverage = dwLanguageCoverages(tags, platformWithTwoOfOurs, choices = emptyList())[odia]!!
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromPlatform)
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromThisApp)
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.offline)
        assertTrue(coverage.servedByInstalled.isEmpty())
        assertTrue(coverage.servedByInstallable.isEmpty())
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
        assertEquals("Works offline", dwPackStateLabel(coverage.offline))
    }

    @Test
    fun `a model that hears Odia and could be installed makes the row downloadable, not installed`() {
        val coverage = dwLanguageCoverages(
            tags, platformWithTwoOfOurs, choices = choicesOf(NOT_A_REAL_ODIA_MODEL)
        )[odia]!!
        assertEquals(DwPackState.DOWNLOADABLE, coverage.fromThisApp)
        assertEquals(listOf(NOT_A_REAL_ODIA_MODEL.modelId), coverage.servedByInstallable)
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
        assertEquals(DwPackState.UNKNOWN, coverage.offline)
    }

    @Test
    fun `a language no model hears is not made unknown by an unreadable disk`() {
        val coverage = dwLanguageCoverages(
            tags,
            platformWithTwoOfOurs,
            choices = choicesOf(NOT_A_REAL_ODIA_MODEL),
            installKnown = false,
        )["ta-IN"]!!
        assertEquals(DwPackState.NO_OFFLINE_PACK, coverage.fromThisApp)
    }

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

    @Test
    fun `a model claiming a bare language serves every region of it, and never the other way round`() {
        val bare = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("or"))
        assertTrue("a model that hears “or” hears or-IN", dwModelServesLanguage(bare, "or-IN"))

        val american = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("en-US"))
        assertFalse(dwModelServesLanguage(american, "en-IN"))

        val untidy = NOT_A_REAL_ODIA_MODEL.copy(languages = listOf("OR_in"))
        assertTrue(dwModelServesLanguage(untidy, "or-IN"))
    }

    @Test
    fun `a model with no measured languages never serves anything`() {
        tags.forEach { tag ->
            assertFalse(dwModelServesLanguage(NOT_A_REAL_UNCHECKED_MODEL, tag))
        }
    }

    @Test
    fun `an unknown outranks both kinds of no, because it has not said no`() {
        assertEquals(DwPackState.UNKNOWN, dwOfflineCoverage(DwPackState.UNKNOWN, DwPackState.NO_OFFLINE_PACK))
        assertEquals(DwPackState.UNKNOWN, dwOfflineCoverage(DwPackState.UNSUPPORTED, DwPackState.UNKNOWN))
    }

    @Test
    fun `between two nos the measured one survives`() {
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

    @Test
    fun `the row counts offline languages against a total, and never rounds`() {
        val states = dwPackStates(tags, platformWithTwoOfOurs)
        val line = dwSpeechSummaryLine(states, DwAsrModelState.NOT_INSTALLED)
        assertEquals("2 of 19 languages work offline", line)
    }

    @Test
    fun `one offline language takes the singular verb`() {
        val onlyHindi = DwRecognitionSupport(installedOnDevice = listOf("hi-IN"))
        val line = dwSpeechSummaryLine(dwPackStates(tags, onlyHindi), DwAsrModelState.NOT_INSTALLED)
        assertEquals("1 of 19 languages works offline", line)
    }

    @Test
    fun `zero offline languages says so without claiming dictation is broken`() {
        val nothing = DwRecognitionSupport(installedOnDevice = emptyList(), supportedOnDevice = tags)
        val line = dwSpeechSummaryLine(dwPackStates(tags, nothing), DwAsrModelState.NOT_INSTALLED)
        assertEquals("No languages work offline", line)
        assertFalse(line.contains("cannot"))
        assertFalse(line.contains("unavailable"))
    }

    @Test
    fun `a verified model of ours is counted with the packs and never twice`() {
        val states = dwPackStates(tags, platformWithTwoOfOurs)
        val line = dwSpeechSummaryLine(
            states,
            DwAsrModelState.INSTALLED,
            modelServedTags = setOf("hi-IN", "or-IN"),
        )
        assertEquals("3 of 19 languages work offline \u00B7 speech model installed", line)
    }

    @Test
    fun `a model of ours cannot turn an unasked platform into a claim`() {
        val line = dwSpeechSummaryLine(
            dwPackStates(tags, null),
            DwAsrModelState.INSTALLED,
            modelServedTags = setOf("hi-IN"),
            canAsk = false,
        )
        assertEquals(
            "Can't list installed packs on this Android version \u00B7 speech model installed",
            line
        )
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
        assertEquals("No offline pack", dwPackStateLabel(coverage.offline))
    }

    @Test
    fun `an installed model short of storage for a fresh download still serves its language`() {
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
