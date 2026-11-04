package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwTier2ModelsTest {

    private val mib = 1024L * 1024L

    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

    private val thirtyTwoBitOnly = fleetHandset.copy(abis = listOf("armeabi-v7a", "armeabi"))

    private val roomy = DwDeviceMeasurement(
        totalRamBytes = 12_000L * mib,
        availableRamBytes = 8_000L * mib,
        lowRamDevice = false,
        freeStorageBytes = 80_000L * mib,
        abis = listOf("arm64-v8a"),
    )

    @Test
    fun `every row carries a weighed file, a named handset for its memory figure, and no language claim`() {
        assertEquals(
            "the catalogue is the two Gemma 4 artifacts; the gated Gemma 3n ones are not offered",
            2,
            DW_TIER2_PLANS.size
        )
        assertEquals("DW_TIER2_CATALOGUE must delegate here, not hold a second copy", DW_TIER2_PLANS, DW_TIER2_CATALOGUE)

        DW_TIER2_PLANS.forEach { plan ->
            val artifact = dwTier2ArtifactFor(plan.modelId)
            assertNotNull("every plan needs the file it is a plan for", artifact)
            assertEquals(
                "${plan.modelId}: the plan's on-disk size and the artifact's byte count are one fact",
                artifact!!.bytes,
                plan.onDiskBytes
            )
            assertFalse(
                "an artifact behind a shipped row may not need a licence accepted upstream first",
                artifact.needsUpstreamApproval
            )
            assertTrue(
                "a digest a phone would check must have been taken here, not read off the host",
                artifact.digestProvenance.startsWith("MEASURED")
            )

            assertTrue(
                "${plan.modelId}: the memory figure must name the handset it came off",
                plan.measuredOn.contains("S26 Ultra")
            )
            assertTrue(
                "${plan.modelId}: and must say it is published rather than measured here",
                plan.measuredOn.contains("published") && plan.measuredOn.contains("not a reading")
            )

            assertNull(
                "${plan.modelId}: languages must be null — the word “unmeasured” — because an " +
                    "upstream README's count is a claim about a family",
                plan.languages
            )
            assertTrue(
                "the claim itself is carried, labelled as a claim, so nobody goes looking for it",
                plan.unmeasuredLanguagesNote?.contains("CLAIM") == true
            )
            assertTrue(plan.accuracy.isEmpty())
            assertNull("nobody has timed a token on a handset in this fleet", plan.realTimeFactor)
            assertNull("nobody has backgrounded a loaded model on any handset here", plan.survivesBackgrounding)

            assertEquals(DW_TIER2_MEASURED_CONTEXT_TOKENS, plan.contextCapTokens)
            assertTrue(plan.runBound.contains("2,048"))
            assertEquals("arm64-v8a", plan.abi)
        }
    }

    @Test
    fun `the memory figures are the CPU ones, because the GPU reading is a floor nobody has reproduced`() {
        val e2b = DW_TIER2_PLANS.first { it.modelId.contains("E2B") }
        val e4b = DW_TIER2_PLANS.first { it.modelId.contains("E4B") }
        assertEquals(1733L * mib, e2b.peakRssBytes)
        assertEquals(3283L * mib, e4b.peakRssBytes)
        assertEquals(676L * mib, dwTier2GpuClaimBytes(e2b.modelId))
        assertEquals(710L * mib, dwTier2GpuClaimBytes(e4b.modelId))
        DW_TIER2_PLANS.forEach { plan ->
            val gpu = dwTier2GpuClaimBytes(plan.modelId)
            assertNotNull(gpu)
            assertTrue(
                "${plan.modelId}: the row must be built from the LARGER of the two published figures",
                plan.peakRssBytes > gpu!!
            )
        }
    }

    @Test
    fun `both models are judged on every handset, and only the arithmetic decides the verdict`() {
        listOf(fleetHandset, thirtyTwoBitOnly, roomy, DwDeviceMeasurement()).forEach { device ->
            val choices = dwModelChoices(DW_TIER2_PLANS, device, tier = DwAiTier.TIER_2)
            assertEquals(
                "every measured model is listed on every device — refused ones included",
                DW_TIER2_PLANS.size,
                choices.size
            )
            choices.forEach { choice ->
                assertEquals(
                    "${choice.plan.modelId} on this device must read the same as dwModelFit says",
                    dwModelFit(choice.plan, device).fit,
                    choice.fit
                )
            }
        }
    }

    @Test
    fun `on the fleet handset both models are tight on this minute's free memory, and neither is refused`() {
        dwModelChoices(DW_TIER2_PLANS, fleetHandset, tier = DwAiTier.TIER_2).forEach { choice ->
            assertEquals(
                "${choice.plan.modelId} should be TIGHT on the fleet handset, not refused",
                DwModelFit.TIGHT,
                choice.fit
            )
            assertEquals(
                listOf(DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW),
                choice.notes
            )
            assertTrue("a tight fit is an overridable one", choice.fit.mayInstall)
            assertNotNull(
                "the row has to be able to say how short it is, and negative is kept as negative",
                choice.freeRamHeadroomBytes
            )
            assertTrue(choice.freeRamHeadroomBytes!! < 0L)
        }
    }

    @Test
    fun `a 32-bit handset is refused for the runtime it has no build of, not for its memory`() {
        dwModelChoices(DW_TIER2_PLANS, thirtyTwoBitOnly, tier = DwAiTier.TIER_2).forEach { choice ->
            assertEquals(DwModelFit.WILL_NOT_FIT, choice.fit)
            assertEquals(listOf(DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR), choice.notes)
            assertFalse(choice.fit.mayInstall)
            val sentence = dwTier2RowSentence(choice, thirtyTwoBitOnly)
            assertTrue(
                "the sentence must name the processor rather than implying the phone is too small",
                sentence.contains("processor")
            )
        }
    }

    @Test
    fun `a roomy phone is comfortable with both, which is what proves nothing here is a blanket no`() {
        dwModelChoices(DW_TIER2_PLANS, roomy, tier = DwAiTier.TIER_2).forEach { choice ->
            assertEquals(DwModelFit.COMFORTABLE, choice.fit)
            assertTrue(choice.notes.isEmpty())
        }
    }

    @Test
    fun `a download is offered exactly where the fit allows it, with a connection, and the runtime in the APK`() {
        assertTrue("LiteRT-LM is in this APK", DW_TIER2_RUNTIME_PRESENT)
        listOf(fleetHandset, roomy, thirtyTwoBitOnly, DwDeviceMeasurement()).forEach { device ->
            dwModelChoices(DW_TIER2_PLANS, device, tier = DwAiTier.TIER_2).forEach { choice ->
                DwConnection.entries.forEach { connection ->
                    assertEquals(
                        "${choice.plan.modelId} on $connection: the gate is the fit verdict and a " +
                            "connection, nothing else",
                        dwTier2Eligible(choice) && connection != DwConnection.NONE,
                        dwTier2InstallMayBeOffered(choice, connection)
                    )
                }
            }
        }
        dwModelChoices(DW_TIER2_PLANS, thirtyTwoBitOnly, tier = DwAiTier.TIER_2).forEach { choice ->
            assertFalse(dwTier2InstallMayBeOffered(choice, DwConnection.UNMETERED))
        }
        val comfortable = dwModelChoices(DW_TIER2_PLANS, roomy, tier = DwAiTier.TIER_2).first()
        assertFalse(dwTier2InstallMayBeOffered(comfortable, DwConnection.UNMETERED, runtimePresent = false))
    }

    @Test
    fun `a row states the size, the memory and the verdict, and never a transcription sentence`() {
        dwModelChoices(DW_TIER2_PLANS, fleetHandset, tier = DwAiTier.TIER_2).forEach { choice ->
            val sentence = dwTier2RowSentence(choice, fleetHandset)
            assertTrue("the size is stated before the tap", sentence.contains("to download"))
            assertTrue("and the memory it needs", sentence.contains("of memory while it runs"))
            assertTrue(
                "the memory figure must be attributed in the same breath",
                sentence.contains("Google's") && sentence.contains("S26 Ultra")
            )
            assertFalse(
                "a language model does not transcribe, and a row that says it does teaches a " +
                    "designer to expect dictation from it",
                sentence.contains("transcribe")
            )
            listOf("nothing has been measured", "unmeasured", "not built", "yet", "server").forEach {
                assertFalse("a row may not narrate what is missing: “$it” in $sentence", sentence.contains(it))
            }
            assertTrue(
                "a row sentence is ${sentence.split(Regex("\\s+")).size} words; over 80 is an essay",
                sentence.split(Regex("\\s+")).size <= 80
            )
        }
    }

    @Test
    fun `the list opens with what the models are for and that nothing downloads by itself`() {
        assertTrue(DW_TIER2_LIST_INTRO.contains("Language models"))
        assertTrue(DW_TIER2_LIST_INTRO.contains("translating"))
        assertTrue(DW_TIER2_LIST_INTRO.contains("unless you ask"))
        listOf("no runtime", "not built", "yet", "nobody").forEach {
            assertFalse(DW_TIER2_LIST_INTRO.contains(it))
        }
    }

    @Test
    fun `a file is verified by size AND digest, whichever cable it arrived on`() {
        val pinned = DW_TIER2_ARTIFACTS.first { it.modelId == "gemma-4-E2B-it.litertlm" }
        assertEquals(
            DwTier2FileVerdict.VERIFIED,
            dwTier2VerifyFile(pinned.modelId, pinned.bytes, pinned.sha256)
        )
        assertEquals(
            "case is a spelling of a digest, not a difference in bytes",
            DwTier2FileVerdict.VERIFIED,
            dwTier2VerifyFile(pinned.modelId, pinned.bytes, pinned.sha256.uppercase())
        )
        assertEquals(
            DwTier2FileVerdict.ABSENT,
            dwTier2VerifyFile(pinned.modelId, null, null)
        )
        assertEquals(
            DwTier2FileVerdict.WRONG_SIZE,
            dwTier2VerifyFile(pinned.modelId, pinned.bytes - 1L, pinned.sha256)
        )
        assertEquals(
            "the same length with different bytes is the substitution that matters",
            DwTier2FileVerdict.WRONG_DIGEST,
            dwTier2VerifyFile(pinned.modelId, pinned.bytes, "0".repeat(64))
        )
        assertEquals(
            "a present file with no digest taken is NOT a pass — hashing is not optional",
            DwTier2FileVerdict.WRONG_DIGEST,
            dwTier2VerifyFile(pinned.modelId, pinned.bytes, null)
        )
        assertEquals(
            DwTier2FileVerdict.NOT_PINNED,
            dwTier2VerifyFile("something-nobody-published.litertlm", 1L, "a".repeat(64))
        )
    }

    @Test
    fun `every artifact refuses to exist without a digest, a size and a repository`() {
        val good = DW_TIER2_ARTIFACTS.first()
        listOf(
            { good.copy(sha256 = "") },
            { good.copy(sha256 = "not-a-digest") },
            { good.copy(bytes = 0L) },
            { good.copy(repo = "") },
            { good.copy(fileName = "../databases/workshop.db") },
            { good.copy(digestProvenance = " ") },
        ).forEach { build ->
            val thrown = runCatching { build() }.exceptionOrNull()
            assertTrue(
                "a Tier 2 artifact must not be constructible without its evidence: got $thrown",
                thrown is IllegalArgumentException
            )
        }
    }

    @Test
    fun `the two artifacts are ungated, measured here, and fetched from a URL pinned to a revision`() {
        assertEquals(2, DW_TIER2_ARTIFACTS.size)
        DW_TIER2_ARTIFACTS.forEach { artifact ->
            assertTrue(artifact.repo.startsWith("litert-community/"))
            assertFalse(artifact.needsUpstreamApproval)
            assertTrue(artifact.digestProvenance.startsWith("MEASURED"))
            assertTrue(
                "the URL is the artifact's own repository, at a 40-hex revision, ending in its file name",
                Regex("^https://huggingface\\.co/${Regex.escape(artifact.repo)}/resolve/[0-9a-f]{40}/" +
                    Regex.escape(artifact.fileName) + "$").matches(artifact.url)
            )
        }
        val good = DW_TIER2_ARTIFACTS.first()
        listOf(
            { good.copy(url = "http://huggingface.co/x/resolve/main/${good.fileName}") },
            { good.copy(url = "https://example.test/${good.fileName}") },
        ).forEach { build ->
            assertTrue(runCatching { build() }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
