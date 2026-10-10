package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **TIER 2 ON THE PHONE: WHO IS OFFERED A MODEL, WHAT A ROW'S CONTROL IS, WHICH MODEL A VERB RUNS ON,
 * AND WHAT IT IS ASKED.** The pure half of `DwTier2Install.kt`, on the desktop JVM.
 */
class DwTier2InstallTest {

    private val mib = 1024L * 1024L

    /** The fleet's SM-M325F at 03:00 on 2026-08-13 — both rows TIGHT, on free memory alone. */
    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )
    private val roomy = DwDeviceMeasurement(
        totalRamBytes = 12_000L * mib,
        availableRamBytes = 8_000L * mib,
        lowRamDevice = false,
        freeStorageBytes = 80_000L * mib,
        abis = listOf("arm64-v8a"),
    )
    private val thirtyTwoBitOnly = fleetHandset.copy(abis = listOf("armeabi-v7a", "armeabi"))
    private val goEdition = fleetHandset.copy(
        totalRamBytes = 1_900L * mib,
        availableRamBytes = 400L * mib,
        lowRamDevice = true,
    )

    private fun choices(device: DwDeviceMeasurement) =
        dwModelChoices(DW_TIER2_PLANS, device, tier = DwAiTier.TIER_2)

    private val e2b get() = DW_TIER2_PLANS.first { it.modelId == "gemma-4-E2B-it.litertlm" }
    private val e4b get() = DW_TIER2_PLANS.first { it.modelId == "gemma-4-E4B-it.litertlm" }

    private val notInstalled = DwTier2ModelStatus(DwTier2ModelState.NOT_INSTALLED)

    // -----------------------------------------------------------------------------------------
    // Eligibility by the measured device tier
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a phone the reading admits is offered a download, and only with a connection`() {
        choices(roomy).forEach { choice ->
            assertEquals(DwTier2InstallOffer.DOWNLOAD, dwTier2InstallOffer(choice, notInstalled, DwConnection.UNMETERED))
            assertEquals(DwTier2InstallOffer.DOWNLOAD, dwTier2InstallOffer(choice, notInstalled, DwConnection.METERED))
            assertEquals(DwTier2InstallOffer.NO_CONNECTION, dwTier2InstallOffer(choice, notInstalled, DwConnection.NONE))
        }
    }

    @Test
    fun `a phone the reading rules out is not offered the model at all, and the card shows nothing`() {
        listOf(thirtyTwoBitOnly, goEdition).forEach { device ->
            val list = choices(device)
            list.forEach { choice ->
                assertFalse("${choice.plan.modelId} must not be eligible here", dwTier2Eligible(choice))
                DwConnection.entries.forEach { connection ->
                    assertEquals(
                        DwTier2InstallOffer.NOT_OFFERED,
                        dwTier2InstallOffer(choice, notInstalled, connection)
                    )
                }
            }
            assertFalse(
                "a phone that may take no model is shown no Tier 2 list and no Tier 2 line",
                dwTier2Visible(list, list.associate { it.plan.modelId to notInstalled })
            )
            assertNull(dwTier2TierLine(dwRecommendTiers(device, DwConnection.UNMETERED).tier2, emptySet()))
        }
    }

    @Test
    fun `a build without the runtime offers nothing anywhere`() {
        choices(roomy).forEach { choice ->
            assertEquals(
                DwTier2InstallOffer.NOT_OFFERED,
                dwTier2InstallOffer(choice, notInstalled, DwConnection.UNMETERED, runtimePresent = false)
            )
        }
        assertFalse(dwTier2Visible(choices(roomy), emptyMap(), runtimePresent = false))
    }

    @Test
    fun `a tight phone is offered the model, behind the override confirmation`() {
        choices(fleetHandset).forEach { choice ->
            assertEquals(DwModelFit.TIGHT, choice.fit)
            assertEquals(DwTier2InstallOffer.DOWNLOAD, dwTier2InstallOffer(choice, notInstalled, DwConnection.METERED))
            assertTrue("a TIGHT download asks first", dwModelNeedsConsent(choice))
            assertNotNull(dwModelOverrideSentence(choice))
        }
        assertTrue(dwTier2Visible(choices(fleetHandset), emptyMap()))
    }

    @Test
    fun `what is on the disk outranks every gate`() {
        val refused = choices(thirtyTwoBitOnly).first()
        assertEquals(
            "an installed model is never hidden because the reading moved",
            DwTier2InstallOffer.INSTALLED,
            dwTier2InstallOffer(refused, DwTier2ModelStatus(DwTier2ModelState.INSTALLED), DwConnection.NONE)
        )
        val choice = choices(roomy).first()
        assertEquals(
            DwTier2InstallOffer.RESUME,
            dwTier2InstallOffer(choice, DwTier2ModelStatus(DwTier2ModelState.PAUSED, partialBytes = 10L), DwConnection.METERED)
        )
        assertEquals(
            DwTier2InstallOffer.RETRY,
            dwTier2InstallOffer(choice, DwTier2ModelStatus(DwTier2ModelState.FAILED, failure = "x"), DwConnection.METERED)
        )
        assertEquals(
            DwTier2InstallOffer.IN_PROGRESS,
            dwTier2InstallOffer(choice, DwTier2ModelStatus(DwTier2ModelState.DOWNLOADING), DwConnection.NONE)
        )
        assertEquals(
            "an unread disk draws nothing rather than a guess",
            DwTier2InstallOffer.CHECKING,
            dwTier2InstallOffer(choice, DwTier2ModelStatus(), DwConnection.UNMETERED)
        )
        assertTrue(
            dwTier2Visible(
                choices(thirtyTwoBitOnly),
                mapOf(e2b.modelId to DwTier2ModelStatus(DwTier2ModelState.INSTALLED)),
            )
        )
    }

    @Test
    fun `every offer has one label or none, and the labels are the speech model's words`() {
        assertEquals("Download", dwTier2ActionLabel(DwTier2InstallOffer.DOWNLOAD))
        assertEquals("Resume", dwTier2ActionLabel(DwTier2InstallOffer.RESUME))
        assertEquals("Try again", dwTier2ActionLabel(DwTier2InstallOffer.RETRY))
        assertEquals("Remove", dwTier2ActionLabel(DwTier2InstallOffer.INSTALLED))
        listOf(
            DwTier2InstallOffer.NO_CONNECTION, DwTier2InstallOffer.IN_PROGRESS,
            DwTier2InstallOffer.NOT_OFFERED, DwTier2InstallOffer.CHECKING,
        ).forEach { assertNull(dwTier2ActionLabel(it)) }
        assertTrue(
            dwTier2StateLine(DwTier2InstallOffer.RESUME, DwTier2ModelStatus(DwTier2ModelState.PAUSED, 500_000_000L))!!
                .contains("500 MB")
        )
    }

    // -----------------------------------------------------------------------------------------
    // Which model a verb runs on, and when
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a verb runs on the smallest verified model, never one that failed to load here`() {
        assertNull(dwTier2ModelToRun(emptySet(), emptyList()))
        assertEquals(e4b, dwTier2ModelToRun(setOf(e4b.modelId), emptyList()))
        assertEquals(e2b, dwTier2ModelToRun(setOf(e2b.modelId, e4b.modelId), emptyList()))
        val failed = DwLoadFailureNote(DwAiTier.TIER_2, e2b.modelId, e2b.contextCapTokens, "OOM")
        assertEquals(
            "a failure is data: the model that would not load here is not tried again",
            e4b,
            dwTier2ModelToRun(setOf(e2b.modelId, e4b.modelId), listOf(failed))
        )
        assertNull(dwTier2ModelToRun(setOf(e2b.modelId), listOf(failed)))
        assertNull(dwTier2ModelToRun(setOf(e2b.modelId), emptyList(), runtimePresent = false))
        val tier1Failure = failed.copy(tier = DwAiTier.TIER_1)
        assertEquals("a Tier 1 failure says nothing about a Tier 2 model", e2b, dwTier2ModelToRun(setOf(e2b.modelId), listOf(tier1Failure)))
    }

    @Test
    fun `the run window is the design's own — never beside capture, never on a hot phone`() {
        val model = e2b
        assertNull(dwTier2RunRefusal(DwTier2Verb.PROOFREAD, 200, model, false, DwThermalState.NONE))
        assertNull(dwTier2RunRefusal(DwTier2Verb.TRANSLATION, 200, model, false, DwThermalState.UNMEASURED))
        assertEquals(DwTier2RunRefusal.NOT_NOW, dwTier2RunRefusal(DwTier2Verb.PROOFREAD, 200, model, true, DwThermalState.NONE))
        assertEquals(DwTier2RunRefusal.NOT_NOW, dwTier2RunRefusal(DwTier2Verb.PROOFREAD, 200, model, false, DwThermalState.SEVERE))
        assertEquals(DwTier2RunRefusal.NO_MODEL, dwTier2RunRefusal(DwTier2Verb.PROOFREAD, 200, null, false, DwThermalState.NONE))
        assertEquals(
            "a passage beyond the measured envelope takes the cloud path",
            DwTier2RunRefusal.PASSAGE_TOO_LONG,
            dwTier2RunRefusal(DwTier2Verb.PROOFREAD, DW_TIER2_MAX_PASSAGE_CHARS + 1, model, false, DwThermalState.NONE)
        )
        assertEquals(
            "expansion is the design's last verb and stays in the cloud",
            DwTier2RunRefusal.VERB_NOT_ON_DEVICE,
            dwTier2RunRefusal(DwTier2Verb.EXPANDED, 200, model, false, DwThermalState.NONE)
        )
        assertEquals(setOf(DwTier2Verb.PROOFREAD, DwTier2Verb.TRANSLATION), DW_TIER2_ON_DEVICE_VERBS)
    }

    // -----------------------------------------------------------------------------------------
    // The prompts, the output, the provenance
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a proofread keeps the language and the craft words, and a translation names its target`() {
        val proofread = dwTier2SystemInstruction(DwTier2Verb.PROOFREAD)
        assertTrue(proofread.contains("same language"))
        assertTrue(proofread.contains("craft terms"))
        assertTrue(proofread.contains("Do not translate"))
        val translate = dwTier2SystemInstruction(DwTier2Verb.TRANSLATION, "Odia")
        assertTrue(translate.contains("into Odia"))
        assertTrue(translate.contains("Do not summarise"))
        assertTrue(runCatching { dwTier2SystemInstruction(DwTier2Verb.TRANSLATION, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { dwTier2SystemInstruction(DwTier2Verb.EXPANDED) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `output is recorded verbatim apart from surrounding whitespace, and nothing is not a layer`() {
        assertEquals("The dabu paste.", dwTier2OutputText("  The dabu paste.\n"))
        assertNull(dwTier2OutputText("   \n"))
        assertNull(dwTier2OutputText(null))
    }

    @Test
    fun `a run records the runtime, the exact model and the language it wrote`() {
        val provenance = dwTier2Provenance(e2b, "Odia", "2026-10-10T09:30:00+05:30")
        assertEquals(DW_TIER2_PROVIDER, provenance.provider)
        assertEquals(e2b.modelId, provenance.modelId)
        assertEquals(DW_TIER2_RUNTIME_VERSION, provenance.modelVersion)
        assertEquals("Odia", dwTier2RunLanguage(DwTier2Verb.TRANSLATION, " Odia ", null))
        assertEquals("multi", dwTier2RunLanguage(DwTier2Verb.PROOFREAD, null, null))
        assertEquals("hi-IN", dwTier2RunLanguage(DwTier2Verb.PROOFREAD, null, "hi-IN"))
        val body = dwTier2LayerBody(
            DwTier2Draft(DwTier2Verb.TRANSLATION, DwTier2Source.SuppliedText("ହାତୀ"), "elephant", provenance)
        )
        assertEquals("TRANSLATION", body["kind"])
        assertEquals("ହାତୀ", body["sourceText"])
        assertFalse("the route fixes the tier", body.containsKey("tier"))
    }

    // -----------------------------------------------------------------------------------------
    // The words on the card
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the Tier 2 line names what runs here, or the suggestion, or nothing — never a refusal`() {
        val installedLine = dwTier2TierLine(DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW), setOf(e2b.modelId))
        assertNotNull(installedLine)
        assertTrue(installedLine!!.contains(e2b.modelId))
        assertNull(dwTier2TierLine(DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_RAM_NOW), emptySet()))
        DwTierRefusal.entries.forEach { refusal ->
            assertNull(dwTier2TierLine(DwTierOffer.None(refusal), emptySet()))
        }
        val offered = dwTier2TierLine(dwRecommendTiers(roomy, DwConnection.UNMETERED).tier2, emptySet())
        assertNotNull(offered)
        assertFalse("a language model does not transcribe recordings", offered!!.contains("recording takes"))
    }

    @Test
    fun `no Tier 2 string narrates a missing feature or names the plumbing`() {
        val strings = listOf(
            DW_TIER2_LIST_INTRO,
            DW_TIER2_MISMATCH_SENTENCE,
            DW_TIER2_WORKING_SENTENCE,
            dwTier2VerbNote(e2b),
            dwTier2StateLine(DwTier2InstallOffer.INSTALLED, DwTier2ModelStatus(DwTier2ModelState.INSTALLED))!!,
            dwTier2StateLine(DwTier2InstallOffer.NO_CONNECTION, notInstalled)!!,
        ) + choices(fleetHandset).map { dwTier2RowSentence(it, fleetHandset) } +
            listOf(
                DwTierRefusal.NO_MEASURED_MODEL, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD,
                DwTierRefusal.RUNTIME_NOT_INSTALLED, DwTierRefusal.RUNTIME_UNMEASURED,
            ).map { dwTierRefusalSentence(DwAiTier.TIER_2, it) }
        val forbidden = listOf(
            "not built", "not been built", "yet", "coming soon", "server", "endpoint", "HTTP",
            "this build", "no runtime", "nobody", "fault in this app",
        )
        strings.forEach { text ->
            forbidden.forEach { word ->
                assertFalse("“$word” in: $text", text.contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun `the gap admissions this feature replaced are gone from the source`() {
        // Code only: the KDoc keeps dated quotations of what these sentences used to say, which is
        // history rather than something a designer reads.
        fun code(path: String) = File(path).readLines().filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString(" ")
        val models = code("src/main/java/com/designprototype/workshop/data/DwTier2Models.kt")
        listOf(
            "No model here can run yet",
            "nothing has been measured on this phone",
            "Cannot be judged",
            "requires a licence to be accepted",
        ).forEach { assertFalse("DwTier2Models.kt still says “$it”", models.contains(it)) }
        val tier = code("src/main/java/com/designprototype/workshop/data/DwDeviceTier.kt")
        listOf(
            "is not in this app yet",
            "has no speech engine of its own yet",
            "Seeing this is a fault in this app",
            "Seeing this would be a fault in this app",
        ).forEach { assertFalse("DwDeviceTier.kt still says “$it”", tier.contains(it)) }
    }
}
