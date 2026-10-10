package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE TIER 2 CATALOGUE, AND THE FOUR THINGS IT MUST NEVER STOP BEING TRUE ABOUT.**
 *
 * `DwDeviceTierTest`'s old test *"the real catalogues are empty, because nothing has been weighed"*
 * asserted `DW_TIER2_CATALOGUE.isEmpty()` and said in its own comment that adding a row without a
 * measurement "should require deleting this test, which is a conversation". This file is the other half
 * of that conversation: the rows exist now, and what replaces the emptiness assertion is a set of
 * assertions about the EVIDENCE behind each row — that the size came off a file somebody weighed, that
 * the memory figure names the handset it was published for, and that a claim is never printed in a
 * measurement's voice.
 */
class DwTier2ModelsTest {

    private val mib = 1024L * 1024L

    /** The fleet's SM-M325F, read off the handset at 03:00 on 2026-08-13. Not invented. */
    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

    /** A 32-bit-only handset. The one shape that is refused outright, and not for its memory. */
    private val thirtyTwoBitOnly = fleetHandset.copy(abis = listOf("armeabi-v7a", "armeabi"))

    /** A phone with plenty of everything, so a COMFORTABLE row can be exercised at all. */
    private val roomy = DwDeviceMeasurement(
        totalRamBytes = 12_000L * mib,
        availableRamBytes = 8_000L * mib,
        lowRamDevice = false,
        freeStorageBytes = 80_000L * mib,
        abis = listOf("arm64-v8a"),
    )

    // -----------------------------------------------------------------------------------------
    // The rows, and the evidence behind each number in them
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every row carries a weighed file, a named handset for its memory figure, and no language claim`() {
        assertEquals(
            "the catalogue is the two Gemma 4 artifacts; the gated Gemma 3n ones are not offered",
            2,
            DW_TIER2_PLANS.size
        )
        assertEquals("DW_TIER2_CATALOGUE must delegate here, not hold a second copy", DW_TIER2_PLANS, DW_TIER2_CATALOGUE)

        DW_TIER2_PLANS.forEach { plan ->
            // The size is not off a model card: an artifact with the same byte count was weighed, and
            // this is the assertion that the two lists cannot drift apart.
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

            // THE PROVENANCE OF THE MEMORY FIGURE IS IN THE FIELD THAT GETS PRINTED. Google measured
            // it on an S26 Ultra; this fleet measured nothing, and the row says both.
            assertTrue(
                "${plan.modelId}: the memory figure must name the handset it came off",
                plan.measuredOn.contains("S26 Ultra")
            )
            assertTrue(
                "${plan.modelId}: and must say it is published rather than measured here",
                plan.measuredOn.contains("published") && plan.measuredOn.contains("not a reading")
            )

            // A CLAIM MAY NOT BECOME A CAPABILITY. Google say 35+ languages; nobody has checked one.
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

            // The envelope the memory figure was taken over, stated in the units it was taken in.
            assertEquals(DW_TIER2_MEASURED_CONTEXT_TOKENS, plan.contextCapTokens)
            assertTrue(plan.runBound.contains("2,048"))
            assertEquals("arm64-v8a", plan.abi)
        }
    }

    @Test
    fun `the memory figures are the CPU ones, because the GPU reading is a floor nobody has reproduced`() {
        /*
         * THE ONE PLACE A TUNED NUMBER WOULD HAVE MADE BOTH MODELS LOOK COMFORTABLE. Google publish
         * 676 MiB (E2B) and 710 MiB (E4B) for the GPU backend against 1733 and 3283 for the CPU. The
         * smaller figure is `ru_maxrss`, which does not count GPU or dmabuf allocations, and whether
         * the GPU path initialises at all on this fleet's Mali-G52 is unmeasured. A row built from it
         * would be this app choosing the flattering half of somebody else's measurement.
         */
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

    // -----------------------------------------------------------------------------------------
    // The verdict comes from the existing rules, on every device shape
    // -----------------------------------------------------------------------------------------

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
                // The verdict is dwModelFit's, not a second opinion computed in the Tier 2 file.
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
        // The reading is real and so is the arithmetic: 1733 MiB and 3283 MiB peaks against 5.93 GB
        // total is nowhere near the line, and 2.59 GB and 3.66 GB against 39.97 GB free storage is
        // nowhere near the other one. What is short is free memory AT THIS INSTANT, which is the
        // overridable kind, because memory frees.
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

    // -----------------------------------------------------------------------------------------
    // The gate: nothing may be fetched, and the reason is the runtime rather than the phone
    // -----------------------------------------------------------------------------------------

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
        // A 32-bit phone is never offered either model: the AAR has no 32-bit build.
        dwModelChoices(DW_TIER2_PLANS, thirtyTwoBitOnly, tier = DwAiTier.TIER_2).forEach { choice ->
            assertFalse(dwTier2InstallMayBeOffered(choice, DwConnection.UNMETERED))
        }
        // And a build without the runtime offers nothing anywhere.
        val comfortable = dwModelChoices(DW_TIER2_PLANS, roomy, tier = DwAiTier.TIER_2).first()
        assertFalse(dwTier2InstallMayBeOffered(comfortable, DwConnection.UNMETERED, runtimePresent = false))
    }

    // -----------------------------------------------------------------------------------------
    // The words: terse, and never a claim in a measurement's voice
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a row states the size, the memory and the verdict, and never a transcription sentence`() {
        dwModelChoices(DW_TIER2_PLANS, fleetHandset, tier = DwAiTier.TIER_2).forEach { choice ->
            val sentence = dwTier2RowSentence(choice, fleetHandset)
            assertTrue("the size is stated before the tap", sentence.contains("to download"))
            assertTrue("and the memory it needs", sentence.contains("of memory while it runs"))
            assertFalse(
                "a language model does not transcribe, and a row that says it does teaches a " +
                    "designer to expect dictation from it",
                sentence.contains("transcribe")
            )
            listOf("nothing has been measured", "unmeasured", "not built", "yet", "server").forEach {
                assertFalse("a row may not narrate what is missing: “$it” in $sentence", sentence.contains(it))
            }
            assertTrue(
                "a row sentence is ${sentence.split(Regex("\\s+")).size} words; over 60 is an essay",
                sentence.split(Regex("\\s+")).size <= 60
            )
        }
    }

    @Test
    fun `the list opens with what the models are for and that nothing downloads by itself`() {
        assertTrue(DW_TIER2_LIST_INTRO.contains("proofreading and translating"))
        assertTrue(DW_TIER2_LIST_INTRO.contains("unless you ask"))
        listOf("no runtime", "not built", "yet", "nobody").forEach {
            assertFalse(DW_TIER2_LIST_INTRO.contains(it))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Sideloading goes through the same check as a download
    // -----------------------------------------------------------------------------------------

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
        // The constructor is the enforcement mechanism, so it is worth one test that it is.
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
