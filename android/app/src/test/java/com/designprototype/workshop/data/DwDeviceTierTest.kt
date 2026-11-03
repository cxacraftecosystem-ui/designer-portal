package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDeviceTierTest {

    private val mib: Long = 1024L * 1024L

    private val fourGigClassPhone = DwDeviceMeasurement(
        totalRamBytes = 3_700L * mib,
        availableRamBytes = 1_100L * mib,
        lowRamDevice = false,
        freeStorageBytes = 12_000L * mib,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        thermal = DwThermalState.NONE,
        charging = false,
    )

    private val twelveGigClassPhone = fourGigClassPhone.copy(
        totalRamBytes = 11_800L * mib,
        availableRamBytes = 6_000L * mib,
        freeStorageBytes = 90_000L * mib,
    )

    private val goEditionPhone = fourGigClassPhone.copy(
        totalRamBytes = 1_900L * mib,
        availableRamBytes = 400L * mib,
        lowRamDevice = true,
        freeStorageBytes = 3_000L * mib,
    )

    private val phoneThatWouldNotAnswer = DwDeviceMeasurement()

    private val NOT_A_REAL_MODEL = DwModelPlan(
        modelId = "not-a-real-model-invented-for-this-test",
        quantisation = "int4",
        abi = "arm64-v8a",
        languages = listOf("or-IN", "as-IN"),
        contextCapTokens = 2048,
        runBound = "one generation at the invented 2,048-token context cap above",
        onDiskBytes = 900L * mib,
        peakRssBytes = 1_200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    private val NOT_A_REAL_ENGINE = DwAsrArtifact(
        abi = "arm64-v8a",
        url = "https://example.invalid/not-a-real-engine/arm64-v8a.zip",
        sha256 = "a".repeat(64),
        downloadBytes = 20L * mib,
        libraries = listOf(DwAsrLibrary("libsherpa-onnx-jni.so", "b".repeat(64), 24L * mib)),
        upstreamVersion = "no upstream — this artifact is a test fixture",
        provenance = "invented in DwDeviceTierTest; nothing was downloaded to produce it",
    )

    private val engineInstalled = DwAsrRuntimeStatus(
        state = DwAsrRuntimeState.INSTALLED,
        artifact = NOT_A_REAL_ENGINE,
        verifiedSha256 = NOT_A_REAL_ENGINE.libraries.map { it.sha256 },
    )

    @Test
    fun `every Tier 2 row carries the handset its memory figure came off, and no runtime can load it`() {
        assertTrue(
            "the catalogue is no longer empty, and each row must name the phone its peak RSS came off",
            DW_TIER2_CATALOGUE.isNotEmpty()
        )
        DW_TIER2_CATALOGUE.forEach { plan ->
            assertTrue(
                "${plan.modelId}: a peak RSS with no handset behind it cannot be checked",
                plan.measuredOn.isNotBlank()
            )
            assertTrue(
                "${plan.modelId}: a published figure may not be printed as a local measurement",
                plan.measuredOn.contains("published")
            )
            assertNull(
                "${plan.modelId}: an upstream language count is a claim, and this field is acted on",
                plan.languages
            )
        }
        assertTrue("LiteRT-LM is in this APK since 2026-10-10", DW_TIER2_RUNTIME_PRESENT)
    }

    @Test
    fun `every shipped Tier 1 row carries evidence, and names the handset it came off`() {
        assertTrue(
            "the engine is in this APK — 23,646,824 bytes at lib/arm64-v8a/libsherpa-onnx-jni.so, " +
                "read off the installed base.apk — so this constant says so",
            DW_TIER1_RUNTIME_PRESENT
        )
        assertTrue("and a row that can be offered needs an engine to run it", DW_TIER1_CATALOGUE.isNotEmpty())
        DW_TIER1_CATALOGUE.forEach { plan ->
            assertTrue(
                "a peak RSS with no handset behind it cannot be checked by anybody",
                plan.measuredOn.isNotBlank() && plan.peakRssBytes > 0L
            )
            assertTrue(
                "a model named without the envelope its memory figure was measured over has not " +
                    "said what will be run",
                plan.runBound.isNotBlank()
            )
            assertNotNull(
                "a shipped row has to say how much slower than real time it decodes, or a designer " +
                    "cannot be told what they are about to wait",
                plan.realTimeFactor
            )
            assertTrue(
                "and it has to have been scored against something, or “does it work” is unanswered",
                plan.accuracy.isNotEmpty()
            )
        }
    }

    @Test
    fun `no shipped row claims a language it was measured to transcribe badly`() {
        DW_TIER1_CATALOGUE.forEach { plan ->
            plan.accuracy.forEach { score ->
                if (score.werPercent >= 50.0) {
                    assertFalse(
                        "${plan.modelId} scored ${score.werPercent}% WER on ${score.tag} — more " +
                            "than half the words wrong — and must not be claimed to serve it. The " +
                            "measurement stays in `accuracy`, where the card prints it as “measured " +
                            "and NOT offered”, because that is a far more useful answer to a " +
                            "designer than silence.",
                        plan.servesLanguage(score.tag)
                    )
                }
            }
            plan.languages?.forEach { tag ->
                assertNotNull(
                    "${plan.modelId} claims $tag with no accuracy row behind it. An unscored claim " +
                        "is exactly what the null-means-unmeasured rule exists to keep out of this list.",
                    plan.accuracyFor(tag)
                )
            }
        }
    }

    @Test
    fun `one accuracy row per language, so a score cannot be shadowed by an earlier duplicate`() {
        DW_TIER1_CATALOGUE.forEach { plan ->
            plan.accuracy.forEach { score ->
                val reachable = plan.accuracyFor(score.tag)
                assertSame(
                    "${plan.modelId} has an accuracy row for ${score.tag} that accuracyFor() cannot " +
                        "reach — an earlier row covers the same language, so this measurement is " +
                        "invisible to every surface that prints one. Merge them or drop one, and if " +
                        "the two disagree say which handset and which corpus each came from.",
                    score,
                    reachable
                )
            }
        }
    }

    @Test
    fun `with the runtime in the APK, each handset's Tier 2 answer comes from its own numbers`() {
        assertEquals(
            "a 12 GB phone with 6 GB free takes the larger weighed row",
            "gemma-4-E4B-it.litertlm",
            (dwRecommendTiers(twelveGigClassPhone, DwConnection.UNMETERED).tier2
                as DwTierOffer.Available).plan.modelId
        )
        assertEquals(
            "a 4 GB-class phone with 1.1 GB free is short of free memory for the smallest row",
            DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW),
            dwRecommendTiers(fourGigClassPhone, DwConnection.UNMETERED).tier2
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.ABI_UNMEASURED),
            dwRecommendTiers(phoneThatWouldNotAnswer, DwConnection.UNMETERED).tier2
        )
        listOf(fourGigClassPhone, twelveGigClassPhone, phoneThatWouldNotAnswer, goEditionPhone)
            .forEach { device ->
                assertNotEquals(
                    DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
                    dwRecommendTiers(device, DwConnection.UNMETERED).tier2
                )
            }
        val sentence = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        assertFalse(
            "no sentence may narrate work that has not been built",
            sentence.contains("not been built") || sentence.contains("yet")
        )
        assertTrue(
            "it must say where the work is done instead of blaming the phone",
            sentence.contains("online")
        )
        assertTrue(
            "it must say the work still happens, or it reads as a capability lost",
            sentence.contains("whenever there is signal")
        )
    }

    @Test
    fun `a low-memory handset is refused by its own numbers and not by the band it lands in`() {
        assertEquals(
            DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL),
            dwRecommendTiers(goEditionPhone, DwConnection.UNMETERED).tier2
        )
        assertEquals(
            "and it is this phone's own numbers that refuse it",
            DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL),
            dwBestPlan(DW_TIER2_CATALOGUE, goEditionPhone, emptyList())
        )
    }

    @Test
    fun `a build with no engine in it refuses Tier 1 for the engine, which is a different sentence`() {
        listOf(fourGigClassPhone, twelveGigClassPhone, goEditionPhone).forEach { device ->
            assertEquals(
                DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
                dwRecommendTiers(device, DwConnection.UNMETERED, runtimeInApk = false).tier1
            )
        }
        assertTrue(
            dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
                .contains("speech engine")
        )
    }

    @Test
    fun `the Tier 1 refusal does not deny the offline dictation the card above already offers`() {
        val tier1 = dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        val tier2 = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        assertTrue(
            "Tier 1's absence is not Tier 2's absence, and one sentence cannot say both",
            tier1 != tier2
        )
        assertTrue("it has to say WHOSE engine is missing", tier1.contains("own speech engine"))
        assertTrue(
            "and it has to name the offline dictation that exists, rather than denying it",
            tier1.contains("dictation")
        )
    }

    @Test
    fun `a low-memory handset is NOT refused Tier 1 for its size`() {
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
            dwRecommendTiers(goEditionPhone, DwConnection.NONE, runtimeInApk = false).tier1
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL),
            dwRecommendTiers(goEditionPhone, DwConnection.NONE).tier1
        )
    }

    @Test
    fun `with the engine in the APK, Tier 1 goes straight to the model question`() {
        listOf(fourGigClassPhone, twelveGigClassPhone, goEditionPhone, phoneThatWouldNotAnswer)
            .forEach { device ->
                val refusal = (dwRecommendTiers(device, DwConnection.UNMETERED).tier1
                    as? DwTierOffer.None)?.refusal
                assertTrue(
                    "no handset may be told there is no engine in a build that contains one — got " +
                        "$refusal",
                    refusal != DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD
                )
                assertTrue(
                    "nor that no model has been measured, because one has — got $refusal",
                    refusal != DwTierRefusal.NO_MEASURED_MODEL
                )
            }
    }

    @Test
    fun `an installable engine that is not installed points at the card that installs it`() {
        val offer = dwRecommendTiers(
            fourGigClassPhone,
            DwConnection.UNMETERED,
            runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
            catalogue1 = listOf(NOT_A_REAL_MODEL),
            artifacts = listOf(NOT_A_REAL_ENGINE),
            runtimeInApk = false,
        ).tier1
        assertEquals(DwTierOffer.None(DwTierRefusal.RUNTIME_NOT_INSTALLED), offer)
        val sentence = dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.RUNTIME_NOT_INSTALLED)
        assertTrue(
            "the one refusal whose next move is a control on the same screen must name it",
            sentence.contains("card above")
        )
        assertTrue(
            "and it must say the choice is the designer's, not a capability they have lost",
            sentence.contains("optional download")
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.RUNTIME_NOT_INSTALLED),
            dwRecommendTiers(
                fourGigClassPhone,
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.DOWNLOADING, NOT_A_REAL_ENGINE),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
    }

    @Test
    fun `an engine installed with no model to feed it blames the model, not the engine`() {
        val offer = dwRecommendTiers(
            fourGigClassPhone,
            DwConnection.NONE,
            runtime = engineInstalled,
            catalogue1 = emptyList(),
            artifacts = listOf(NOT_A_REAL_ENGINE),
            runtimeInApk = false,
        ).tier1
        assertEquals(DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL), offer)

        val tier1 = dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_MEASURED_MODEL)
        val tier2 = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_MEASURED_MODEL)
        assertTrue("one sentence cannot carry both missing things", tier1 != tier2)
        assertTrue("Tier 1's missing artifact is a speech model, and it says so", tier1.contains("speech model"))
        assertTrue("both still refuse in words rather than going silent", tier2.contains("nothing to install"))
    }

    @Test
    fun `an engine this handset cannot take is told which limit stopped it`() {
        assertEquals(
            DwTierOffer.None(DwTierRefusal.ABI_NOT_BUILT_FOR),
            dwRecommendTiers(
                fourGigClassPhone.copy(abis = listOf("x86_64")),
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE),
            dwRecommendTiers(
                fourGigClassPhone.copy(freeStorageBytes = 100L * mib),
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.ABI_UNMEASURED),
            dwRecommendTiers(
                fourGigClassPhone.copy(abis = emptyList()),
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.FREE_STORAGE_UNMEASURED),
            dwRecommendTiers(
                fourGigClassPhone.copy(freeStorageBytes = null),
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
    }

    @Test
    fun `a phone whose own files could not be read is unmeasured, not offered a second copy`() {
        assertEquals(
            DwTierOffer.None(DwTierRefusal.RUNTIME_UNMEASURED),
            dwRecommendTiers(
                fourGigClassPhone,
                DwConnection.UNMETERED,
                runtime = DwAsrRuntimeStatus(DwAsrRuntimeState.UNKNOWN),
                catalogue1 = listOf(NOT_A_REAL_MODEL),
                artifacts = listOf(NOT_A_REAL_ENGINE),
                runtimeInApk = false,
            ).tier1
        )
    }

    @Test
    fun `with an engine installed and a model measured, Tier 1 finally becomes an offer`() {
        val roomy = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            availableRamBytes = 2_000L * mib,
            freeStorageBytes = 4_000L * mib,
        )
        val offer = dwRecommendTiers(
            roomy,
            DwConnection.UNMETERED,
            runtime = engineInstalled,
            catalogue1 = listOf(NOT_A_REAL_MODEL),
            artifacts = listOf(NOT_A_REAL_ENGINE),
            runtimeInApk = false,
        ).tier1
        assertTrue("an installed engine plus a measured model is the one path to an offer", offer is DwTierOffer.Available)
        assertEquals(NOT_A_REAL_MODEL, (offer as DwTierOffer.Available).plan)
    }

    @Test
    fun `a status claiming an engine nobody verified cannot even be built`() {
        assertTrue(
            runCatching { engineInstalled.copy(verifiedSha256 = emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertFalse(dwAsrMayLoad(DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, NOT_A_REAL_ENGINE)))
        assertTrue(dwAsrMayLoad(engineInstalled))
    }

    @Test
    fun `nothing is ever offered without a connection, and Tier 2 is never offered at all`() {
        val devices = listOf(
            fourGigClassPhone, twelveGigClassPhone, goEditionPhone, phoneThatWouldNotAnswer
        )
        devices.forEach { device ->
            DwConnection.entries.forEach { connection ->
                val recommendation = dwRecommendTiers(device, connection)
                val offered2 = recommendation.tier2 as? DwTierOffer.Available
                if (offered2 != null) {
                    assertTrue(
                        "a Tier 2 offer is always one of the weighed rows",
                        offered2.plan in DW_TIER2_CATALOGUE
                    )
                }
                if (connection == DwConnection.NONE) {
                    assertFalse(dwTierDownloadMayBeOffered(recommendation.tier2, connection))
                    assertFalse(
                        "a download control on a phone with no connection is worse than none at all",
                        dwTierDownloadMayBeOffered(recommendation.tier1, connection)
                    )
                }
                val offered = recommendation.tier1 as? DwTierOffer.Available
                if (offered != null) {
                    assertTrue(
                        "anything this app offers to download has to be a row somebody measured on " +
                            "a named handset",
                        offered.plan in DW_TIER1_CATALOGUE && offered.plan.measuredOn.isNotBlank()
                    )
                }
            }
        }
    }

    @Test
    fun `Tier 3 is available whenever there is a connection, and only then`() {
        assertTrue(dwRecommendTiers(fourGigClassPhone, DwConnection.METERED).tier3Available)
        assertTrue(dwRecommendTiers(fourGigClassPhone, DwConnection.UNMETERED).tier3Available)
        assertFalse(dwRecommendTiers(fourGigClassPhone, DwConnection.NONE).tier3Available)
    }

    @Test
    fun `a measured model with no runtime to load it is still nothing you can run`() {
        val recommendation = dwRecommendTiers(
            twelveGigClassPhone,
            DwConnection.UNMETERED,
            catalogue2 = listOf(NOT_A_REAL_MODEL),
            tier2RuntimeInApk = false,
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
            recommendation.tier2
        )
    }

    @Test
    fun `a plan cannot be built without the envelope its peak RSS was measured over`() {
        val zeroCap = runCatching { NOT_A_REAL_MODEL.copy(contextCapTokens = 0) }.exceptionOrNull()
        assertTrue(zeroCap is IllegalArgumentException)
        assertTrue(
            "the refusal must be a sentence naming the next move, not a code",
            (zeroCap?.message ?: "").contains("context cap")
        )
        val noBound = runCatching { NOT_A_REAL_MODEL.copy(runBound = "  ") }.exceptionOrNull()
        assertTrue(noBound is IllegalArgumentException)
        assertTrue((noBound?.message ?: "").contains("bounds"))
        assertNull(DW_TIER1_CATALOGUE.first().contextCapTokens)
        assertTrue(DW_TIER1_CATALOGUE.first().runBound.isNotBlank())
    }

    @Test
    fun `a plan cannot be built without a measured peak RSS or the handset it came from`() {
        assertTrue(
            runCatching { NOT_A_REAL_MODEL.copy(peakRssBytes = 0L) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertTrue(
            runCatching { NOT_A_REAL_MODEL.copy(measuredOn = "  ") }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertTrue(
            runCatching { NOT_A_REAL_MODEL.copy(onDiskBytes = 0L) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertTrue(
            runCatching { NOT_A_REAL_MODEL.copy(quantisation = "") }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    @Test
    fun `a phone that would not say how much memory it has is unmeasured, not small`() {
        assertEquals(DwDeviceClass.UNMEASURED, dwDeviceClass(phoneThatWouldNotAnswer))
        assertEquals(
            DwDeviceClass.UNMEASURED,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = null, lowRamDevice = null))
        )
    }

    @Test
    fun `a zero total is treated as unmeasured rather than as a phone with no memory`() {
        assertEquals(
            DwDeviceClass.UNMEASURED,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = 0L, lowRamDevice = null))
        )
    }

    @Test
    fun `Android's own low-RAM flag outranks the byte count beside it`() {
        val flaggedButRoomy = twelveGigClassPhone.copy(lowRamDevice = true)
        assertEquals(DwDeviceClass.LOW_RAM, dwDeviceClass(flaggedButRoomy))
    }

    @Test
    fun `the bands place each handset in the measurement doc's rows`() {
        assertEquals(DwDeviceClass.LOW_RAM, dwDeviceClass(goEditionPhone))
        assertEquals(DwDeviceClass.SMALL_4GB, dwDeviceClass(fourGigClassPhone))
        assertEquals(
            DwDeviceClass.MID_6_TO_8GB,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = 5_700L * mib))
        )
        assertEquals(
            DwDeviceClass.MID_6_TO_8GB,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = 7_500L * mib))
        )
        assertEquals(DwDeviceClass.LARGE_12GB_PLUS, dwDeviceClass(twelveGigClassPhone))
    }

    @Test
    fun `a phone sold as 4 GB reports less than 4 GB and still lands in the 4 GB row`() {
        listOf(3_500L, 3_700L, 3_900L).forEach { reported ->
            assertEquals(
                "a handset reporting ${reported} MiB is a 4 GB phone",
                DwDeviceClass.SMALL_4GB,
                dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = reported * mib))
            )
        }
    }

    @Test
    fun `a phone sold as 3 GB lands in the low-memory row wherever in the twos it reports`() {
        listOf(2_100L, 2_500L, 2_749L, 2_800L, 3_000L, 3_071L).forEach { reported ->
            assertEquals(
                "a handset reporting ${reported} MiB is at most a 3 GB phone",
                DwDeviceClass.LOW_RAM,
                dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = reported * mib))
            )
        }
        assertEquals(
            DwDeviceClass.SMALL_4GB,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = 3_072L * mib))
        )
    }

    @Test
    fun `no band edge sits below the most the row under it can report`() {
        assertTrue("a 3 GB handset cannot report more than 3 GiB", DW_LOW_RAM_CEILING_BYTES >= 3L * 1024L * mib)
        assertTrue("a 4 GB handset cannot report more than 4 GiB", DW_FOUR_GB_CEILING_BYTES >= 4L * 1024L * mib)
        assertTrue("an 8 GB handset cannot report more than 8 GiB", DW_EIGHT_GB_CEILING_BYTES >= 8L * 1024L * mib)
    }

    @Test
    fun `a plan that fits reports the memory that would be left over`() {
        val roomy = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            availableRamBytes = 2_000L * mib,
            freeStorageBytes = 4_000L * mib,
        )
        assertNull(dwPlanFits(NOT_A_REAL_MODEL, roomy))
        val offer = dwBestPlan(listOf(NOT_A_REAL_MODEL), roomy, emptyList())
        assertTrue(offer is DwTierOffer.Available)
        assertEquals(800L * mib, (offer as DwTierOffer.Available).headroomBytes)
    }

    @Test
    fun `an unmeasured free-memory reading refuses rather than passes`() {
        val silent = fourGigClassPhone.copy(availableRamBytes = null, totalRamBytes = 5_700L * mib)
        assertEquals(DwTierRefusal.FREE_RAM_UNMEASURED, dwPlanFits(NOT_A_REAL_MODEL, silent))
    }

    @Test
    fun `an unmeasured storage reading refuses, and is reported before the memory one`() {
        val silent = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            freeStorageBytes = null,
            availableRamBytes = null,
        )
        assertEquals(DwTierRefusal.FREE_STORAGE_UNMEASURED, dwPlanFits(NOT_A_REAL_MODEL, silent))
    }

    @Test
    fun `an unreadable ABI list refuses instead of quietly matching nothing`() {
        val noAbis = fourGigClassPhone.copy(totalRamBytes = 5_700L * mib, abis = emptyList())
        assertEquals(DwTierRefusal.ABI_UNMEASURED, dwPlanFits(NOT_A_REAL_MODEL, noAbis))
    }

    @Test
    fun `a handset with no build for its processor is told so, not told to free memory`() {
        val thirtyTwoBitOnly = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            abis = listOf("armeabi-v7a"),
        )
        assertEquals(DwTierRefusal.ABI_NOT_BUILT_FOR, dwPlanFits(NOT_A_REAL_MODEL, thirtyTwoBitOnly))
    }

    @Test
    fun `a model larger than the whole phone is a durable no, not a not-right-now`() {
        val small = fourGigClassPhone.copy(
            totalRamBytes = 1_600L * mib,
            availableRamBytes = 1_500L * mib,
            lowRamDevice = false,
        )
        assertEquals(DwTierRefusal.DEVICE_TOO_SMALL, dwPlanFits(NOT_A_REAL_MODEL, small))
    }

    @Test
    fun `enough memory in total but not enough free right now says exactly that`() {
        val busy = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            availableRamBytes = 1_500L * mib,
            freeStorageBytes = 4_000L * mib,
        )
        assertEquals(DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW, dwPlanFits(NOT_A_REAL_MODEL, busy))
        assertTrue(
            dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW)
                .contains("Check again")
        )
    }

    @Test
    fun `a model is refused storage it would fill to the brim`() {
        val tight = fourGigClassPhone.copy(
            totalRamBytes = 5_700L * mib,
            availableRamBytes = 2_000L * mib,
            freeStorageBytes = 1_500L * mib,
        )
        assertEquals(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE, dwPlanFits(NOT_A_REAL_MODEL, tight))
    }

    @Test
    fun `the largest plan that fits is chosen, and the choice is deterministic`() {
        val bigger = NOT_A_REAL_MODEL.copy(
            modelId = "also-not-real-bigger",
            peakRssBytes = 1_600L * mib,
            onDiskBytes = 1_400L * mib,
        )
        val roomy = fourGigClassPhone.copy(
            totalRamBytes = 11_800L * mib,
            availableRamBytes = 4_000L * mib,
            freeStorageBytes = 20_000L * mib,
        )
        listOf(
            listOf(NOT_A_REAL_MODEL, bigger),
            listOf(bigger, NOT_A_REAL_MODEL),
        ).forEach { catalogue ->
            val offer = dwBestPlan(catalogue, roomy, emptyList())
            assertEquals(bigger, (offer as DwTierOffer.Available).plan)
        }
    }

    @Test
    fun `when nothing fits, the reason given is the smallest plan's reason`() {
        val bigger = NOT_A_REAL_MODEL.copy(
            modelId = "also-not-real-bigger",
            peakRssBytes = 3_500L * mib,
        )
        val noRoom = fourGigClassPhone.copy(
            totalRamBytes = 3_700L * mib,
            availableRamBytes = 3_000L * mib,
            freeStorageBytes = 1_500L * mib,
        )
        assertEquals(DwTierRefusal.DEVICE_TOO_SMALL, dwPlanFits(bigger, noRoom))
        assertEquals(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE, dwPlanFits(NOT_A_REAL_MODEL, noRoom))

        val offer = dwBestPlan(listOf(NOT_A_REAL_MODEL, bigger), noRoom, emptyList())
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE),
            offer
        )
    }

    @Test
    fun `a model that failed to load on this handset is not offered again`() {
        val roomy = fourGigClassPhone.copy(
            totalRamBytes = 11_800L * mib,
            availableRamBytes = 4_000L * mib,
            freeStorageBytes = 20_000L * mib,
        )
        val note = DwLoadFailureNote(
            tier = DwAiTier.TIER_2,
            modelId = NOT_A_REAL_MODEL.modelId,
            contextCapTokens = NOT_A_REAL_MODEL.contextCapTokens,
            detail = "the runtime returned an out-of-memory error while mapping the weights",
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.LOAD_FAILED_HERE_BEFORE),
            dwBestPlan(listOf(NOT_A_REAL_MODEL), roomy, listOf(note))
        )
    }

    @Test
    fun `the same weights at a different cap is a different run and is still offered`() {
        val roomy = fourGigClassPhone.copy(
            totalRamBytes = 11_800L * mib,
            availableRamBytes = 4_000L * mib,
            freeStorageBytes = 20_000L * mib,
        )
        val smallerCap = NOT_A_REAL_MODEL.copy(contextCapTokens = 1024)
        val note = DwLoadFailureNote(
            tier = DwAiTier.TIER_2,
            modelId = NOT_A_REAL_MODEL.modelId,
            contextCapTokens = 2048,
            detail = "out of memory at the 2K cap",
        )
        val offer = dwBestPlan(listOf(smallerCap), roomy, listOf(note))
        assertEquals(smallerCap, (offer as DwTierOffer.Available).plan)
    }

    @Test
    fun `a failed load falls back to the server when there is signal, and says what changed`() {
        val note = DwLoadFailureNote(DwAiTier.TIER_2, "not-a-real-model", 2048, "OOM")
        val online = dwFallbackAfterLoadFailure(note, DwConnection.METERED)
        assertEquals(DwAiTier.TIER_3, online.goesTo)
        assertTrue(online.sentence.contains("online"))
        assertTrue("the cap belongs in the sentence too", online.sentence.contains("2048"))
    }

    @Test
    fun `a failed load with no connection goes nowhere, and says nothing was lost`() {
        val note = DwLoadFailureNote(DwAiTier.TIER_2, "not-a-real-model", 2048, "OOM")
        val offline = dwFallbackAfterLoadFailure(note, DwConnection.NONE)
        assertNull(offline.goesTo)
        assertTrue(offline.sentence.contains("Nothing has been lost"))
    }

    @Test
    fun `a reading goes stale, and a reading from a mixed clock is stale immediately`() {
        assertFalse(dwProbeIsStale(takenAtElapsedMs = 1_000L, nowElapsedMs = 1_000L))
        assertFalse(dwProbeIsStale(1_000L, 1_000L + DW_PROBE_FRESH_FOR_MS))
        assertTrue(dwProbeIsStale(1_000L, 1_000L + DW_PROBE_FRESH_FOR_MS + 1L))
        assertTrue(dwProbeIsStale(takenAtElapsedMs = 5_000L, nowElapsedMs = 1_000L))
    }

    @Test
    fun `Tier 2 never starts while capture is open, whatever else is true`() {
        val perfect = DwTierOffer.Available(NOT_A_REAL_MODEL, headroomBytes = 4_000L * mib)
        assertEquals(
            DwTier2Window.WAIT_CAPTURE_IS_OPEN,
            dwTier2RunWindow(perfect, capturing = true, thermal = DwThermalState.NONE)
        )
        assertEquals(
            DwTier2Window.RUN_NOW,
            dwTier2RunWindow(perfect, capturing = false, thermal = DwThermalState.NONE)
        )
    }

    @Test
    fun `a hot phone waits, and a phone that cannot report its temperature is not barred for ever`() {
        val perfect = DwTierOffer.Available(NOT_A_REAL_MODEL, headroomBytes = 4_000L * mib)
        assertEquals(
            DwTier2Window.WAIT_DEVICE_IS_HOT,
            dwTier2RunWindow(perfect, capturing = false, thermal = DwThermalState.MODERATE)
        )
        assertEquals(
            DwTier2Window.WAIT_DEVICE_IS_HOT,
            dwTier2RunWindow(perfect, capturing = false, thermal = DwThermalState.SEVERE)
        )
        assertEquals(
            DwTier2Window.RUN_NOW,
            dwTier2RunWindow(perfect, capturing = false, thermal = DwThermalState.UNMEASURED)
        )
        assertFalse(DwThermalState.UNMEASURED.tooHotToStart)
        assertFalse(DwThermalState.LIGHT.tooHotToStart)
    }

    @Test
    fun `with nothing to run the window says so rather than describing a queue nobody is in`() {
        val nothing = dwRecommendTiers(fourGigClassPhone, DwConnection.UNMETERED).tier2
        assertEquals(
            DwTier2Window.NOTHING_TO_RUN,
            dwTier2RunWindow(nothing, capturing = false, thermal = DwThermalState.NONE)
        )
    }

    @Test
    fun `the charger is advice and never a bar, and an unread battery says nothing at all`() {
        assertNotNull(dwTier2PowerAdvice(charging = false))
        assertNull(dwTier2PowerAdvice(charging = true))
        assertNull("an unread BatteryManager must produce silence, not a hedge", dwTier2PowerAdvice(null))
    }

    @Test
    fun `an unmeasured byte count is the word unmeasured, never zero`() {
        assertEquals("unmeasured", dwBytesLabel(null))
        assertEquals("3.0 GB", dwBytesLabel(3_000_000_000L))
        assertEquals("500 MB", dwBytesLabel(500_000_000L))
        assertEquals("3.9 GB", dwBytesLabel(3_700L * mib))
        assertEquals("537 MB", dwBytesLabel(512L * mib))
    }

    @Test
    fun `the readout prints the word unmeasured for every signal the phone withheld`() {
        val sentence = dwDeviceReadoutSentence(phoneThatWouldNotAnswer)
        assertEquals(
            "three memory or storage figures, all of them unmeasured",
            3,
            Regex("unmeasured").findAll(sentence).count()
        )
        assertTrue(
            "a failed low-RAM lookup is not a handset that answered no",
            sentence.contains("could not be read")
        )
        val measured = dwDeviceReadoutSentence(fourGigClassPhone)
        assertTrue(measured.contains("3.9 GB"))
        assertTrue(measured.contains("arm64-v8a"))
        assertFalse(measured.contains("unmeasured"))
    }

    @Test
    fun `every refusal is a distinct sentence, and none of them is a code`() {
        val sentences = DwTierRefusal.entries.map { dwTierRefusalSentence(DwAiTier.TIER_2, it) }
        assertEquals(
            "two refusals sharing one sentence read as one refusal, and these do not mean the same",
            DwTierRefusal.entries.size,
            sentences.toSet().size
        )
        sentences.forEach { sentence ->
            assertTrue("a refusal must be a sentence: $sentence", sentence.trimEnd().endsWith("."))
            assertTrue(
                "says what to do or what happens instead: $sentence",
                listOf("tap", "close", "free some space", "download", "online", "nothing to install", "use the card")
                    .any { sentence.lowercase().contains(it) }
            )
        }
    }

    @Test
    fun `an offer states the model, the download size and the room this phone has — and no more`() {
        val offer = DwTierOffer.Available(NOT_A_REAL_MODEL, headroomBytes = 800L * mib)
        val sentence = dwTierOfferSentence(DwAiTier.TIER_2, offer)
        assertTrue(sentence.contains(NOT_A_REAL_MODEL.modelId))
        assertTrue("an offer may not name a model without its download size", sentence.contains("944 MB"))
        assertTrue("and without what this phone would have left", sentence.contains("839 MB"))
        assertTrue(
            "nothing downloads by itself, and the sentence says so",
            sentence.contains("Nothing is fetched unless you ask for it")
        )

        listOf(
            "2,048-token" to "the run bound's token cap",
            "1.3 GB" to "the peak-memory figure",
            NOT_A_REAL_MODEL.measuredOn to "the handset the figures were measured on",
        ).forEach { (fragment, what) ->
            assertFalse(
                "$what compares candidate models — a decision this app already made — so it does " +
                    "not belong in the offer: $sentence",
                sentence.contains(fragment)
            )
        }
    }

    @Test
    fun `a model that dies when the app is backgrounded says so in the offer`() {
        val fragile = NOT_A_REAL_MODEL.copy(survivesBackgrounding = false)
        val sentence = dwTierOfferSentence(DwAiTier.TIER_2, DwTierOffer.Available(fragile, 800L * mib))
        assertTrue(sentence.contains("photograph"))
    }

    @Test
    fun `the offline Tier 3 sentence promises nothing is lost, and the online one names the craft list`() {
        assertTrue(dwTier3Sentence(DwConnection.NONE).contains("nothing is lost"))
        assertTrue(dwTier3Sentence(DwConnection.METERED).contains("dabu"))
        assertTrue(dwTier3Sentence(DwConnection.UNMETERED).contains("dabu"))
    }

    @Test
    fun `the tier names are the wire values the provenance half already writes down`() {
        assertEquals(listOf("TIER_1", "TIER_2", "TIER_3"), DwAiTier.entries.map { it.name })
        assertEquals(1, DwAiTier.TIER_1.number)
        assertEquals(3, DwAiTier.TIER_3.number)
        assertEquals("on this phone", DwAiTier.TIER_2.where)
        assertEquals("online", DwAiTier.TIER_3.where)
    }
}
