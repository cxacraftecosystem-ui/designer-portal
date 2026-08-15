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
            "the catalogue is the two Gemma 4 artifacts; the two Gemma 3n ones cannot be plans and " +
                "live in DW_TIER2_UNJUDGED",
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
            // And the smaller one still reaches the designer, as a claim, because it is the number
            // that would change the answer if it held.
            assertTrue(
                dwTier2RowSentence(dwModelFit(plan, fleetHandset), fleetHandset).contains("graphics backend")
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The verdict comes from the existing rules, on every device shape
    // -----------------------------------------------------------------------------------------

    @Test
    fun `all four models are visible on every handset, and only the arithmetic decides the verdict`() {
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
        assertEquals(
            "and the two unjudgeable artifacts are listed too, on every device, with no verdict",
            2,
            DW_TIER2_UNJUDGED.size
        )
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
    fun `no handset and no connection may be offered a download while there is no runtime`() {
        assertFalse("this build has no LiteRT-LM runtime", DW_TIER2_RUNTIME_PRESENT)
        listOf(fleetHandset, roomy, thirtyTwoBitOnly, DwDeviceMeasurement()).forEach { device ->
            dwModelChoices(DW_TIER2_PLANS, device, tier = DwAiTier.TIER_2).forEach { choice ->
                DwConnection.entries.forEach { connection ->
                    assertFalse(
                        "a 2.6 GB fetch for a file nothing in this build can open is worse than no " +
                            "control at all",
                        dwTier2InstallMayBeOffered(choice, connection)
                    )
                }
            }
        }
        // AND THE GATE IS THE RUNTIME, NOT THE FIT. With a runtime the same phones would be offered
        // exactly what dwModelDownloadMayBeOffered offers, so the day it lands this needs no thought.
        val comfortable = dwModelChoices(DW_TIER2_PLANS, roomy, tier = DwAiTier.TIER_2).first()
        assertTrue(
            dwTier2InstallMayBeOffered(comfortable, DwConnection.UNMETERED, runtimePresent = true)
        )
        assertFalse(
            "and never with no connection, even then",
            dwTier2InstallMayBeOffered(comfortable, DwConnection.NONE, runtimePresent = true)
        )
    }

    // -----------------------------------------------------------------------------------------
    // The words: terse, and never a claim in a measurement's voice
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a row says whose figure the memory is, and never prints a transcription sentence`() {
        dwModelChoices(DW_TIER2_PLANS, fleetHandset, tier = DwAiTier.TIER_2).forEach { choice ->
            val sentence = dwTier2RowSentence(choice, fleetHandset)
            assertTrue("the size is stated before the tap", sentence.contains("to download"))
            assertTrue(
                "the memory figure must be attributed in the same breath",
                sentence.contains("Google's") && sentence.contains("S26 Ultra")
            )
            assertTrue(
                "and must say nothing was measured on the phone the designer is holding",
                sentence.contains("nothing has been measured on this phone")
            )
            /*
             * THE TWO SENTENCES THIS ROW EXISTS TO AVOID. `dwModelChoiceSentence` would have appended
             * "How accurately it transcribes ANY language is UNMEASURED" and "How long it takes to
             * transcribe a recording on this phone is UNMEASURED" — both true of the speech model they
             * were written for, both nonsense under a proofreader.
             */
            assertFalse(
                "a language model does not transcribe, and a row that says it does teaches a " +
                    "designer to expect dictation from it",
                sentence.contains("transcribe")
            )
            // TERSE. The list that came off this screen was 1,207 words; a row is a scannable thing.
            assertTrue(
                "a row sentence is ${sentence.split(Regex("\\s+")).size} words; over 90 is an essay",
                sentence.split(Regex("\\s+")).size <= 90
            )
        }
    }

    @Test
    fun `the list says once why nothing can be installed, and does not repeat it per row`() {
        val intro = dwTier2ListIntro(DW_TIER2_PLANS.size, DW_TIER2_UNJUDGED.size)
        assertTrue(intro.contains(DW_TIER2_RUNTIME_ABSENCE))
        assertTrue(
            "the absence is about this app rather than about the phone",
            DW_TIER2_RUNTIME_ABSENCE.contains("this app has no runtime")
        )
        assertTrue("and the unjudged rows are accounted for in it", intro.contains("no verdict"))
        dwModelChoices(DW_TIER2_PLANS, fleetHandset, tier = DwAiTier.TIER_2).forEach { choice ->
            assertFalse(
                "the reason belongs in the opening line, once",
                dwTier2RowSentence(choice, fleetHandset).contains("no runtime")
            )
        }
    }

    @Test
    fun `an unjudged row says the size as a number and the memory as the word, and never guesses`() {
        DW_TIER2_UNJUDGED.forEach { model ->
            val sentence = dwTier2UnjudgedSentence(model)
            assertTrue(sentence.contains("to download"))
            assertTrue(
                "the memory has to be the word, in that word",
                sentence.contains("unknown")
            )
            /*
             * THE NUMBERS THAT MUST NOT APPEAR. A Google Developers Blog post says the Gemma 3n family
             * operates "with as little as 2GB (E2B) and 3GB (E4B) of memory". That is a claim about a
             * family, on no named handset, and if it ever reaches a row it will read as this
             * artifact's requirement.
             */
            assertFalse(sentence.contains("2 GB of memory"))
            assertFalse(sentence.contains("3 GB of memory"))
            assertTrue(
                "and the gate has to be named, because it is what stops a phone being given the file",
                sentence.contains("licence")
            )
            assertTrue(
                "an unjudged row is still a weighed row",
                model.onDiskBytes > 3_000_000_000L
            )
        }
        assertEquals("Cannot be judged", DW_TIER2_UNJUDGED_LABEL)
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
    fun `the four artifacts are the four real files, and the gated two are labelled as the host's word`() {
        assertEquals(4, DW_TIER2_ARTIFACTS.size)
        val gated = DW_TIER2_ARTIFACTS.filter { it.needsUpstreamApproval }
        assertEquals("the two google, gated repositories", 2, gated.size)
        gated.forEach { artifact ->
            assertTrue(artifact.repo.startsWith("google/"))
            assertTrue(
                "bytes nobody here has held may not carry a digest labelled as measured",
                artifact.digestProvenance.startsWith("PUBLISHED BY THE HOST")
            )
            assertNotNull(
                "and each gated artifact is one of the rows listed without a verdict",
                DW_TIER2_UNJUDGED.firstOrNull { it.modelId == artifact.modelId }
            )
        }
        DW_TIER2_ARTIFACTS.filterNot { it.needsUpstreamApproval }.forEach { artifact ->
            assertTrue(artifact.repo.startsWith("litert-community/"))
        }
    }
}
