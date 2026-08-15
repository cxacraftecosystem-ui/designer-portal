package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-tier recommender, on the desktop JVM.
 *
 * ── WHAT THIS FILE IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * Every claim this feature makes is a claim about somebody else's handset, and the two ways it can
 * go wrong are opposites. It can claim a capability the app does not have — the failure
 * docs/DEVICE-TIER-MEASUREMENT.md was written to stop, where a model nobody has weighed gets named
 * on a settings screen. Or it can refuse a flagship because an `ActivityManager` lookup failed and a
 * null was quietly read as a zero. Both are pinned below.
 *
 * ── THE FIXTURE RULE THIS FILE OBEYS, AND WHY ─────────────────────────────────────────────────
 *
 * `DwLanguagePackTest` carries the scar: a fixture named after a real handset once asserted
 * capabilities that handset did not have, so the suite agreed with a device that does not exist
 * while the settings screen told seventeen working languages they were unsupported. So the model
 * fixture below is called [NOT_A_REAL_MODEL], every number in it is a round number chosen to make
 * the arithmetic legible, and none of it is a measurement of anything. The DEVICE fixtures are
 * likewise shaped rather than sourced: they are named for the class of handset they stand for, and
 * the memory figures in them are plausible reported totals, not readings taken off a phone.
 *
 * ── AND THE ONE THING THAT WOULD BE A DEFECT ──────────────────────────────────────────────────
 *
 * The most important test in this file is [`no handset anywhere is offered a download today`]. Today
 * the honest answer is that no model exists for any device; the day somebody adds a row to
 * [DW_TIER2_CATALOGUE] without also having measured it, that test is what goes red.
 */
class DwDeviceTierTest {

    private val mib: Long = 1024L * 1024L

    // ---------------------------------------------------------------------------------------
    // Fixtures — shaped, not measured, and named so they cannot be mistaken for measurements
    // ---------------------------------------------------------------------------------------

    /**
     * A handset in the fleet's class: sold as 4 GB, so it reports something in the threes, with a
     * comfortable amount free and an ordinary amount of storage left.
     *
     * NOT A READING FROM A GALAXY M32. No probe has been run on one; when it is, the numbers belong
     * in docs/DEVICE-TIER-MEASUREMENT.md and not in a test fixture.
     */
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

    /** Every signal missing: the shape a handset takes when the service lookups all fail. */
    private val phoneThatWouldNotAnswer = DwDeviceMeasurement()

    /**
     * A MODEL THAT DOES NOT EXIST, and the name says so at every use site on purpose.
     *
     * Its numbers are round because they are there to make the arithmetic readable — 1,200 MiB
     * resident, 900 MiB on disk — not because anything was weighed. The margins the arithmetic keeps
     * ([DW_MODEL_FREE_RAM_MARGIN_BYTES] = 512 MiB, [DW_MODEL_FREE_STORAGE_MARGIN_BYTES] = 1,024 MiB)
     * mean this plan needs 1,712 MiB free of memory and 1,924 MiB free of storage.
     *
     * MiB HERE, "MB" ON SCREEN, AND THE TWO ARE NOT THE SAME NUMBER. Everything in this file is
     * binary because the constants it is checked against are; [dwBytesLabel] divides by 1000 so the
     * letters a designer reads mean what they say, which is why 900 MiB prints as "944 MB".
     */
    private val NOT_A_REAL_MODEL = DwModelPlan(
        modelId = "not-a-real-model-invented-for-this-test",
        quantisation = "int4",
        abi = "arm64-v8a",
        // TWO INVENTED LANGUAGES, AND HINDI IS DELIBERATELY NOT ONE OF THEM. A fixture that claimed
        // the languages the fleet actually uses would be the `DwLanguagePackTest` scar again in a
        // new file: a test agreeing with a capability nobody measured. These two are here so the
        // coverage arithmetic has something to chew on, and `DwModelLanguagesTest` owns the rest.
        languages = listOf("or-IN", "as-IN"),
        contextCapTokens = 2048,
        // A DECODER-SHAPED FIXTURE, so the invented cap above IS the envelope and this restates it.
        // The real Tier 1 row is a CTC speech model with no cap at all and a runBound measured in
        // seconds of audio — see [DwModelPlan.runBound]; this fixture is deliberately the other shape.
        runBound = "one generation at the invented 2,048-token context cap above",
        onDiskBytes = 900L * mib,
        peakRssBytes = 1_200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    /**
     * AN ENGINE ARTIFACT THAT DOES NOT EXIST, for the Tier 1 states that only become reachable once
     * one does. Nothing is published (`DW_ASR_ARTIFACTS` is empty and `DwAsrRuntimeTest` asserts it),
     * so every use of this fixture is a use of an invented row, exactly as [NOT_A_REAL_MODEL] is.
     */
    private val NOT_A_REAL_ENGINE = DwAsrArtifact(
        abi = "arm64-v8a",
        url = "https://example.invalid/not-a-real-engine/arm64-v8a.zip",
        sha256 = "a".repeat(64),
        downloadBytes = 20L * mib,
        libraries = listOf(DwAsrLibrary("libsherpa-onnx-jni.so", "b".repeat(64), 24L * mib)),
        upstreamVersion = "no upstream — this artifact is a test fixture",
        provenance = "invented in DwDeviceTierTest; nothing was downloaded to produce it",
    )

    /** The engine present and fully verified — the only status that may be called installed. */
    private val engineInstalled = DwAsrRuntimeStatus(
        state = DwAsrRuntimeState.INSTALLED,
        artifact = NOT_A_REAL_ENGINE,
        verifiedSha256 = NOT_A_REAL_ENGINE.libraries.map { it.sha256 },
    )

    // ---------------------------------------------------------------------------------------
    // The honest answer today. If any of these change, the shipped promise has changed.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every Tier 2 row carries the handset its memory figure came off, and no runtime can load it`() {
        /*
         * ── THIS TEST USED TO ASSERT `DW_TIER2_CATALOGUE.isEmpty()`, AND ITS OWN COMMENT SAID THAT
         *    ADDING A ROW "should require deleting this test, which is a conversation". THIS IS THE
         *    OTHER HALF OF THAT CONVERSATION. ────────────────────────────────────────────────────
         *
         * The emptiness was standing in for a rule — *this app will not name a model nobody has
         * weighed* — and the rule is what is asserted now, against whatever the catalogue holds. Two
         * artifacts have since been downloaded, weighed and hashed on the release machine
         * (2,588,147,712 and 3,659,530,240 bytes), which answers the first of the measurement doc's two
         * open questions and turns the emptiness assertion into a false statement about the world.
         *
         * **WHAT HAS NOT CHANGED IS THAT NOTHING HAS BEEN RUN ON A HANDSET IN THIS FLEET.** The memory
         * figures are Google's, taken on a Galaxy S26 Ultra, and the assertion below is that every row
         * says so in the field a card prints — because the failure this whole file exists to prevent is
         * a claim printed in a measurement's voice. `DwTier2ModelsTest` carries the rest.
         */
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
        assertFalse("there is no LiteRT or ONNX runtime in this APK", DW_TIER2_RUNTIME_PRESENT)
    }

    /*
     * ── THE TWO ASSERTIONS THAT USED TO LIVE IN THE TEST ABOVE, AND WHY THEY WERE BOTH WRONG ────
     *
     * It used to end:
     *
     *     assertTrue(DW_TIER1_CATALOGUE.isEmpty())
     *     assertFalse("sherpa-onnx is step 4 of the plan and is not in this APK",
     *                 DW_TIER1_RUNTIME_PRESENT)
     *
     * **The second asserted a falsehood in its own failure message.** The engine had been vendored,
     * measured at 23,646,824 bytes inside the packaged APK, and RUN on the fleet's handset — while
     * this line certified that it was not there, and the suite went green over it. That is the exact
     * shape of defect this file's own header says it exists to catch, committed in the file itself.
     *
     * The first was true when written and had stopped being the right thing to pin. It was protecting
     * *"nothing is offered that nobody has weighed"* — a real rule — but it spelled that rule as
     * "the list is empty", which is only the same thing while nothing has been weighed. Something has.
     *
     * **SO NEITHER IS DELETED; BOTH ARE REPLACED BY WHAT THEY WERE REALLY PROTECTING.** The two tests
     * below pin the rule rather than the state: every row in the shipped catalogue carries measured
     * evidence and names the handset it came off, and no row claims a language it scored badly on.
     */

    @Test
    fun `every shipped Tier 1 row carries evidence, and names the handset it came off`() {
        assertTrue(
            "the engine is in this APK — 23,646,824 bytes at lib/arm64-v8a/libsherpa-onnx-jni.so, " +
                "read off the installed base.apk — so this constant says so",
            DW_TIER1_RUNTIME_PRESENT
        )
        assertTrue("and a row that can be offered needs an engine to run it", DW_TIER1_CATALOGUE.isNotEmpty())
        DW_TIER1_CATALOGUE.forEach { plan ->
            // Not "is not blank" — the invariant is that a number can be traced to a real run on a
            // named phone, which is the thing docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md exists
            // because somebody once did not do.
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
        /*
         * **THE TEST THIS WHOLE LANE EXISTS FOR, AND IT IS NOT A TAUTOLOGY.**
         *
         * `languages` is a decision with consequences — a tag in it turns a settings row green, puts
         * `APP_SPEECH_MODEL` on the dictation ladder ahead of the server, and takes the craft-aware
         * rung away from that language. The pinned model hears Odia at **53.3% WER** on studio read
         * speech, which is more than half the words wrong and is a ceiling rather than a field
         * result, so Odia is measured, carried as evidence, and NOT claimed.
         *
         * The bar is asserted here rather than left to a comment because the failure it prevents is
         * silent: somebody adding `or-IN` to that list would make every card in Settings start
         * promising offline Odia, and nothing would go red.
         *
         * **50% IS A CHOSEN LINE AND IT IS DELIBERATELY LOOSE.** It is not the bar plan §2.2 wants —
         * nobody has set that number — it is a floor low enough that only an indefensible claim trips
         * it. Hindi at 24.2% is offered and sits well clear; Odia at 53.3% is not offered and sits
         * well past. Anybody tightening this should measure first and put the number in
         * docs/DEVICE-TIER-MEASUREMENT.md.
         */
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
            // And the other half of the same rule: a language it DOES claim has to have been scored.
            // A claim with no measurement behind it is the thing `languages = null` is for.
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
        /*
         * **WHY THIS IS WORTH A TEST OF ITS OWN, AND IT IS NOT TIDINESS.**
         *
         * [DwModelPlan.accuracyFor] is `accuracy.firstOrNull { dwTagCovers(it.tag, tag) }`, so with two
         * rows for one language the SECOND is unreachable — and the two would not be there in the first
         * place unless somebody had measured that language twice and got two answers. The row a card
         * printed would then be decided by the order of two lines, and the one it hid would be the
         * newer measurement, because new rows get appended.
         *
         * This nearly happened on 2026-08-13: nine languages were appended to the pinned row from a
         * fresh handset run, and re-running Odia in the same run gave CER 13.8 / WER 51.4 against the
         * 15.2 / 53.3 already recorded — close enough to confirm the earlier figures reproduce, and a
         * second `or-IN` row would have buried one of them behind the other. The earlier rows were left
         * alone and only genuinely new languages were added. `dwTagCovers` is the comparison rather than
         * `==` for the same reason every other list here uses it: `or` and `or-IN` are one language, and
         * a duplicate spelled the other way would slip past an equality check.
         */
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
    fun `every handset is refused Tier 2 today, and told it is about the app and not the phone`() {
        /*
         * THE REFUSAL CHANGED VALUE ON 2026-08-13 AND KEPT ITS MEANING. It was NO_MEASURED_MODEL,
         * because the catalogue was empty; two weighed rows are in it now, and what is missing is the
         * runtime — so the honest refusal is NO_RUNTIME_IN_THIS_BUILD. **The property being pinned is
         * the same one and it is the important one: every handset gets the SAME answer, because the
         * missing thing is in this app rather than in anybody's phone.**
         */
        listOf(fourGigClassPhone, twelveGigClassPhone, phoneThatWouldNotAnswer, goEditionPhone)
            .forEach { device ->
                val recommendation = dwRecommendTiers(device, DwConnection.UNMETERED)
                assertEquals(
                    "a 12 GB flagship, a 4 GB handset, a Go-edition phone and a handset that would " +
                        "not answer all get one answer, because none of them is what is missing",
                    DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
                    recommendation.tier2
                )
            }
        val sentence = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        assertTrue(
            "it must say the absence is this app's and not the screen's",
            sentence.contains("work that has not been built")
        )
        assertTrue(
            "it must say the work still happens, or it reads as a capability lost",
            sentence.contains("server")
        )
    }

    @Test
    fun `a low-memory handset is refused by its own numbers and not by the band it lands in`() {
        /*
         * ── THIS TEST USED TO ASSERT THE OPPOSITE ORDER, AND THE ARGUMENT FOR IT EXPIRED ─────────
         *
         * It pinned `DEVICE_TOO_SMALL` for a Go-edition handset ahead of every other refusal, on the
         * reasoning that "nothing has been measured yet" invites a designer back after an update while
         * for this phone the answer would still be no. **The smallest Tier 2 row needs 1,733 MiB**,
         * which fits a 3 GB handset with the app's 512 MiB margin intact — so that premise is gone, and
         * a class-level refusal would have printed "this phone does not have the memory" directly above
         * a row that `dwModelFit` had just marked comfortable.
         *
         * What a Go-edition phone gets TODAY is the same refusal everyone gets: there is no runtime.
         * What it gets once there is one is asserted here through the arithmetic itself, which is the
         * thing that will actually decide it — 1,900 MiB total, `isLowRamDevice` set, and 400 MiB free.
         */
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
            dwRecommendTiers(goEditionPhone, DwConnection.UNMETERED).tier2
        )
        assertEquals(
            "and when a runtime lands, this phone's own numbers are what refuse it",
            DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL),
            dwBestPlan(DW_TIER2_CATALOGUE, goEditionPhone, emptyList())
        )
    }

    @Test
    fun `a build with no engine in it refuses Tier 1 for the engine, which is a different sentence`() {
        /*
         * **THIS TEST USED TO ASSERT THIS OF THE SHIPPED BUILD, AND THAT IS NO LONGER TRUE.** The
         * engine is in the APK, so `dwRecommendTiers` with no `runtimeInApk` argument now goes
         * straight to the model question — which the test two below this one pins.
         *
         * What it was protecting is still worth protecting and is asserted here where it is still
         * reachable: this file may describe what a handset COULD run and must never imply the app can
         * run it, so a build that does NOT carry the engine says the ENGINE is missing rather than
         * saying a model is — because "no model has been measured" tells a designer that a model
         * turning up is all that stands in the way, and on such a build it is not.
         *
         * `runtimeInApk = false` is how that build is reached from here. See [dwTier1Offer], where
         * that parameter's whole purpose is keeping this branch testable after the constant flipped.
         */
        // The distinction the brief insists on: this file may describe what a handset COULD run, and
        // must never imply the app can run it. "No model has been measured" would tell a designer
        // that a model turning up is all that stands in the way. It is not.
        listOf(fourGigClassPhone, twelveGigClassPhone, goEditionPhone).forEach { device ->
            assertEquals(
                DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
                dwRecommendTiers(device, DwConnection.UNMETERED, runtimeInApk = false).tier1
            )
        }
        assertTrue(
            dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
                .contains("not been built")
        )
    }

    @Test
    fun `the Tier 1 refusal does not deny the offline dictation the card above already offers`() {
        /*
         * A CONTRADICTION ON ONE SCREEN, WHICH IS WHAT THIS PINS. The tier card is drawn directly
         * beneath "Offline dictation languages" in Settings, and that card tells a designer of an
         * installed pack that "dictation in it works with no signal". The general no-runtime
         * sentence says "there is no engine in this build that could run a model on this phone" —
         * true of THIS APP'S own engine, and false as a designer reads it two centimetres below a
         * card offering offline speech. A designer who concludes one of the two is lying stops
         * trusting the one that works, which costs them a capability they have.
         *
         * The two and the seventeen behind the sentence are measured, not estimated:
         * docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md is raw logcat off the fleet's own M32.
         */
        val tier1 = dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        val tier2 = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD)
        assertTrue(
            "Tier 1's absence is not Tier 2's absence, and one sentence cannot say both",
            tier1 != tier2
        )
        assertTrue("it has to say WHOSE engine is missing", tier1.contains("of its own"))
        assertTrue(
            "and it has to name the offline dictation that exists, rather than denying it",
            tier1.contains("dictation")
        )
    }

    @Test
    fun `a low-memory handset is NOT refused Tier 1 for its size`() {
        // The measurement doc's first row still gives it "smallest ASR model only". Its Tier 1
        // answer is about this build, not about the handset, and conflating the two would retire a
        // capability from a phone that may well manage it.
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
            dwRecommendTiers(goEditionPhone, DwConnection.NONE, runtimeInApk = false).tier1
        )
        /*
         * AND ON THE SHIPPED BUILD IT IS STILL NOT REFUSED FOR ITS SIZE AS SUCH — it is refused for a
         * reason about the MODEL, which is the distinction this test is really about. Android's own
         * low-RAM flag makes `dwPlanFits` answer DEVICE_TOO_SMALL, which is a sentence about this
         * particular model not fitting this particular phone rather than about the handset being
         * barred from the tier for ever: the measurement doc's first row still gives it "smallest ASR
         * model only", and a smaller row landing tomorrow changes this answer with no code change.
         */
        assertEquals(
            DwTierOffer.None(DwTierRefusal.DEVICE_TOO_SMALL),
            dwRecommendTiers(goEditionPhone, DwConnection.NONE).tier1
        )
    }

    @Test
    fun `with the engine in the APK, Tier 1 goes straight to the model question`() {
        /*
         * **THE SHIPPED WORLD, PINNED.** `DW_TIER1_RUNTIME_PRESENT` is true, so no handset is told
         * "This app has no speech engine of its own on this phone" — which is the sentence a designer
         * was reading on a phone carrying 23.6 MB of exactly that, and the defect this lane existed
         * to close.
         *
         * Every answer below is now about the MODEL: whether this handset can hold 1.26 GB of
         * resident set with the margins intact. That is a fact about the phone, which is what this
         * card is for.
         */
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

    // ---------------------------------------------------------------------------------------
    // Tier 1 after the engine became an opt-in install — the states that used to be one state
    // ---------------------------------------------------------------------------------------

    /*
     * **EVERY CALL IN THIS SECTION NOW PASSES `runtimeInApk = false`, AND THAT IS THE SECTION'S
     * SUBJECT RATHER THAN NOISE.** The engine went into the APK on 2026-08-12 evening, so on the
     * shipped build `dwTier1Offer` never reaches the install branch at all and all six of these
     * refusals became unreachable in one edit. They are not dead: they are what a build WITHOUT the
     * vendored AAR answers, and "no engine build for your processor" is still the honest sentence for
     * a handset that has none. Deleting the tests because the shipped constant moved would have left
     * live code with nothing pinning it — so the constant became a parameter instead, and these tests
     * now say which world they are about. See [dwTier1Offer].
     *
     * WHY THIS SECTION EXISTS. Until 2026-08-12 `dwTier1Offer` returned a constant, and
     * docs/DEVICE-TIER-MEASUREMENT.md said in those words that every device is refused Tier 1 for the
     * absence of an engine. The engine is now something a designer can install (`DwAsrRuntime.kt`), so
     * presence is a fact about the HANDSET, and the honest answers multiply. Every one of them is
     * unreachable in the shipped build — nothing is published — and each is exercised here against
     * openly invented rows, so that the day an artifact IS published the only new thing in the app is
     * a row of constants.
     */

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
        // A fetch already in flight is the same refusal, deliberately: the card above is showing the
        // progress, and a tier sentence claiming "installed" a moment early would be two accounts of
        // one fact on one screen.
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
        /*
         * "THERE IS NO ENGINE" AND "THERE IS AN ENGINE AND NOTHING TO FEED IT" SEND A DESIGNER TO
         * DIFFERENT PLACES — docs/ASR-RUNTIME-MEASUREMENT.md §7 says so in those words, and this is the
         * edit it predicted would be needed the day a runtime landed.
         */
        val offer = dwRecommendTiers(
            fourGigClassPhone,
            DwConnection.NONE,
            runtime = engineInstalled,
            catalogue1 = emptyList(),
            artifacts = listOf(NOT_A_REAL_ENGINE),
            runtimeInApk = false,
        ).tier1
        assertEquals(DwTierOffer.None(DwTierRefusal.NO_MEASURED_MODEL), offer)

        /*
         * And Tier 1's version of that refusal is NOT Tier 2's: the missing artifact is a speech model
         * where Tier 2's is a language model, so they are two sentences and not one.
         *
         * THREE ASSERTIONS WERE DELETED FROM HERE ON 2026-08-13, DELIBERATELY, and what replaced them
         * is the pair below plus `DwSpeechCardProseTest`. Two of them required each sentence to name
         * its own file in this repository — `docs/ASR-RUNTIME-MEASUREMENT.md` and
         * `docs/DEVICE-TIER-MEASUREMENT.md` — and the third required the Tier 1 sentence to contain the
         * word "Odia", on the reasoning that it "names the courtyard, which is the designer's actual
         * problem".
         *
         * All three pinned the surface the repository owner has now rejected three times. A designer
         * holding the phone cannot open a path in this repository, and singling out one language for
         * explanation on a settings card is the special pleading they asked to be rid of in as many
         * words. The refusal itself is what a designer needs and it is still asserted: each tier still
         * says there is nothing to install, and the two still differ.
         */
        val tier1 = dwTierRefusalSentence(DwAiTier.TIER_1, DwTierRefusal.NO_MEASURED_MODEL)
        val tier2 = dwTierRefusalSentence(DwAiTier.TIER_2, DwTierRefusal.NO_MEASURED_MODEL)
        assertTrue("one sentence cannot carry both missing things", tier1 != tier2)
        assertTrue("Tier 1's missing artifact is a speech model, and it says so", tier1.contains("speech model"))
        assertTrue("both still refuse in words rather than going silent", tier2.contains("nothing to install"))
    }

    @Test
    fun `an engine this handset cannot take is told which limit stopped it`() {
        // No build for the processor: durable, about the phone, and reported BEFORE anything about a
        // model — the same ordering argument `dwTier2Offer` makes for DEVICE_TOO_SMALL, because after
        // a model is measured this handset's answer is still no.
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
        // No room: actionable and temporary, so it is reported as such rather than as a verdict.
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
        /*
         * AND THE TWO UNMEASURED ANSWERS, WHICH THE FIT ARITHMETIC'S OWN TESTS DO NOT REACH THIS WAY.
         * `dwPlanFits` is tested for both of these against a MODEL; the engine's install offer is a
         * different function with its own ordering, and docs/DEVICE-TIER-MEASUREMENT.md claims every
         * expressible Tier 1 answer is exercised here. A missing reading refuses rather than passing:
         * neither "which build would this phone need" nor "would it fit" may be guessed.
         */
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
        // Reading "we could not look" as "not installed" would offer a 24 MB download to a designer
        // who already paid for it once. The same rule DwPackState.UNKNOWN follows.
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
        /*
         * THE WHOLE PATH, END TO END, AGAINST INVENTED ROWS — so that the arithmetic behind the day
         * this ships is tested now rather than written then. NOT_A_REAL_MODEL needs 1,200 + 512 MiB of
         * memory and 900 + 1,024 MiB of storage, which this handset has.
         */
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
        /*
         * THE GATE, ASSERTED FROM THIS SIDE TOO. `dwTier1Offer` reads `dwAsrMayLoad`, so if an
         * unverified status could be constructed, the tier card would report a usable engine on the
         * strength of a file nobody hashed — and the next lane would load it. It cannot be constructed:
         * the invariant is in DwAsrRuntimeStatus's own constructor, and DwAsrRuntimeTest pins the rest
         * of its ways of failing.
         */
        assertTrue(
            runCatching { engineInstalled.copy(verifiedSha256 = emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertFalse(dwAsrMayLoad(DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED, NOT_A_REAL_ENGINE)))
        assertTrue(dwAsrMayLoad(engineInstalled))
    }

    @Test
    fun `nothing is ever offered without a connection, and Tier 2 is never offered at all`() {
        /*
         * **THIS TEST USED TO SAY "no handset anywhere is offered a download today" AND THIS FILE'S
         * OWN HEADER CALLED IT THE MOST IMPORTANT TEST IN IT. IT HAS DELIBERATELY BEEN NARROWED, AND
         * THAT IS THE ONE CHANGE IN THIS FILE A REVIEWER SHOULD LOOK AT HARDEST.**
         *
         * It was protecting a real rule and it spelled the rule as a state. The rule is: *a control
         * that spends a designer's data may not be drawn for a model nobody has weighed.* The state
         * it asserted was "no control is ever drawn", which was the same thing only while nothing had
         * been weighed. `DW_TIER1_CATALOGUE` now holds a row whose every number came off a handset,
         * so a phone with room IS offered it — that is the feature working, not the guard failing.
         *
         * What survives, and is asserted below, is everything the rule actually said:
         *
         *  * **Tier 2 is offered on NO handset and NO connection.** The reason moved on 2026-08-13 —
         *    its catalogue holds two weighed rows now and there is no runtime in this build that could
         *    load one — and the assertion is unchanged, because `dwTierDownloadMayBeOffered` reads the
         *    OFFER and the offer is still `None`. What goes red the day somebody flips
         *    `DW_TIER2_RUNTIME_PRESENT` without a fetch path is this line.
         *  * **Nothing is ever offered with no connection**, on either tier — a control that cannot
         *    work is worse than an absent one, because the designer in a courtyard taps it, nothing
         *    happens, and they conclude the app is broken rather than that they are offline.
         *  * **Anything that IS offered is a weighed row**, asserted against the catalogue directly
         *    rather than inferred from the catalogue being empty. That is the rule as a rule, and it
         *    goes on holding after the next measurement lands.
         */
        val devices = listOf(
            fourGigClassPhone, twelveGigClassPhone, goEditionPhone, phoneThatWouldNotAnswer
        )
        devices.forEach { device ->
            DwConnection.entries.forEach { connection ->
                val recommendation = dwRecommendTiers(device, connection)
                assertFalse(
                    "a control that spends a designer's data may not be drawn for a model nobody " +
                        "has weighed, and no Tier 2 model has been",
                    dwTierDownloadMayBeOffered(recommendation.tier2, connection)
                )
                if (connection == DwConnection.NONE) {
                    assertFalse(
                        "a download control on a phone with no connection is worse than none at all",
                        dwTierDownloadMayBeOffered(recommendation.tier1, connection)
                    )
                }
                // THE RULE ITSELF, not the state it used to be spelled as.
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
        // The column of the table that is not unmeasured: the server chain in
        // backend/app/services/ai.py has worked since long before any of this.
        assertTrue(dwRecommendTiers(fourGigClassPhone, DwConnection.METERED).tier3Available)
        assertTrue(dwRecommendTiers(fourGigClassPhone, DwConnection.UNMETERED).tier3Available)
        assertFalse(dwRecommendTiers(fourGigClassPhone, DwConnection.NONE).tier3Available)
    }

    @Test
    fun `a measured model with no runtime to load it is still nothing you can run`() {
        // Two separate facts, two separate refusals. If this ever returned an offer, the app would
        // be recommending a download for an engine that cannot open the file.
        val recommendation = dwRecommendTiers(
            twelveGigClassPhone,
            DwConnection.UNMETERED,
            catalogue2 = listOf(NOT_A_REAL_MODEL),
        )
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NO_RUNTIME_IN_THIS_BUILD),
            recommendation.tier2
        )
    }

    // ---------------------------------------------------------------------------------------
    // A model may not be named without the cap it was measured at
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a plan cannot be built without the envelope its peak RSS was measured over`() {
        /*
         * **THE INVARIANT MOVED RATHER THAN RELAXING, AND THIS TEST MOVED WITH IT.**
         *
         * It used to assert only that `contextCapTokens` could not be zero, on the argument that
         * KV-cache grows with context and can exceed the weights — which is exactly right about a
         * decoder and is not true of anything about a CTC speech model, where audio goes in whole,
         * one result comes out, and there is no context window to configure. The first measured
         * artifact in this repository is one of those, and filling that field with a plausible 2,048
         * to satisfy a `require` would have been the invented value this whole file exists to stop.
         *
         * So `contextCapTokens` became nullable and [DwModelPlan.runBound] became required of every
         * plan. **What cannot be expressed is unchanged** and is what both halves below pin: a
         * recommendation that names a model without saying what was actually run.
         */
        // A CAP OF ZERO IS STILL REFUSED. Null says "this family has no such dial"; zero says "it has
        // one and it is nothing", which is not a model — and zero is the value somebody reaches for
        // to get past the constructor.
        val zeroCap = runCatching { NOT_A_REAL_MODEL.copy(contextCapTokens = 0) }.exceptionOrNull()
        assertTrue(zeroCap is IllegalArgumentException)
        assertTrue(
            "the refusal must be a sentence naming the next move, not a code",
            (zeroCap?.message ?: "").contains("context cap")
        )
        // AND THE ENVELOPE IS REQUIRED OF EVERY PLAN, WITH NO DEFAULT — including the ones that
        // legitimately have no token cap. This is what stops "we recommend this model" from being
        // sayable without also saying what was run.
        val noBound = runCatching { NOT_A_REAL_MODEL.copy(runBound = "  ") }.exceptionOrNull()
        assertTrue(noBound is IllegalArgumentException)
        assertTrue((noBound?.message ?: "").contains("bounds"))
        // A NULL CAP IS ACCEPTED, because a CTC speech model has none — and the shipped row is one.
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

    // ---------------------------------------------------------------------------------------
    // The device class — the left-hand column of the measurement doc's table
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a phone that would not say how much memory it has is unmeasured, not small`() {
        // THE DEFECT A NULL-READ-AS-ZERO WOULD CAUSE. A flagship whose ActivityManager lookup failed
        // must not be classified as a low-memory device with total confidence.
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
        // The flag is the platform's considered verdict about a build, and the OEM configuration
        // behind it knows things about the handset that a memory total does not.
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
        // The trap the band edges exist for: `totalMem` is memory the kernel can see, and the
        // firmware's reservations are taken before Android is told anything. An edge placed at a
        // round 4 GB would put the entire fleet in the low-memory row.
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
        /*
         * THE DEFECT THIS TEST WAS WRITTEN FOR. [DW_LOW_RAM_CEILING_BYTES] was 2,750 MiB, which sits
         * INSIDE the range its own comment said a 3 GB handset reports, so a 3 GB phone reporting
         * 2.8 GiB was classified as a 4 GB-class one. That is the erring-HIGH direction, on the one
         * row of the measurement doc's table whose entire Tier 2 content is the word "none".
         *
         * The rule the edge rests on needs no measurement: reported total is always below the number
         * on the box, so a handset sold as 3 GB reports SOMEWHERE below 3 GiB — anywhere in the twos
         * — and every one of those figures has to land in the same row.
         */
        listOf(2_100L, 2_500L, 2_749L, 2_800L, 3_000L, 3_071L).forEach { reported ->
            assertEquals(
                "a handset reporting ${reported} MiB is at most a 3 GB phone",
                DwDeviceClass.LOW_RAM,
                dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = reported * mib))
            )
        }
        // And the edge is exclusive: a phone that reports a full 3 GiB is not a 3 GB handset, since
        // one of those cannot report all of what is on its box.
        assertEquals(
            DwDeviceClass.SMALL_4GB,
            dwDeviceClass(fourGigClassPhone.copy(totalRamBytes = 3_072L * mib))
        )
    }

    @Test
    fun `no band edge sits below the most the row under it can report`() {
        // The rule stated above the constants, asserted rather than left to the prose: a firmware
        // reservation may only ever push a handset DOWN a row. An edge below the ceiling of the row
        // beneath it lets a shortfall push a phone UP, which is how a 3 GB handset was being offered
        // a 4 GB handset's answer.
        assertTrue("a 3 GB handset cannot report more than 3 GiB", DW_LOW_RAM_CEILING_BYTES >= 3L * 1024L * mib)
        assertTrue("a 4 GB handset cannot report more than 4 GiB", DW_FOUR_GB_CEILING_BYTES >= 4L * 1024L * mib)
        assertTrue("an 8 GB handset cannot report more than 8 GiB", DW_EIGHT_GB_CEILING_BYTES >= 8L * 1024L * mib)
    }

    // ---------------------------------------------------------------------------------------
    // The fit arithmetic, exercised against an openly invented plan
    // ---------------------------------------------------------------------------------------

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
        // The heart of the honest-unknown rule in this file. A missing availMem is not "plenty" and
        // is not "none"; starting a several-hundred-megabyte allocation on the strength of a
        // question nobody answered is how the low-memory killer takes a workshop draft.
        val silent = fourGigClassPhone.copy(availableRamBytes = null, totalRamBytes = 5_700L * mib)
        assertEquals(DwTierRefusal.FREE_RAM_UNMEASURED, dwPlanFits(NOT_A_REAL_MODEL, silent))
    }

    @Test
    fun `an unmeasured storage reading refuses, and is reported before the memory one`() {
        // Storage is checked first because it is the durable half: a model that will not fit on the
        // phone can never be fetched, whereas free memory changes minute to minute.
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
        // "Close some apps and try again" would be a lie on a handset that does not have this much
        // memory in total however much of it happens to be free.
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
        // 900 MB model, 1024 MB margin: 1500 MB free is not enough, because a workshop day fills a
        // phone with photographs and the download must not be what stops the next capture.
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
        // Both orders of the same catalogue must give the same answer; a recommendation that moved
        // between two probes of an unchanged handset would look like a bug to whoever watched it.
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
        /*
         * THE TWO PLANS MUST FAIL FOR DIFFERENT REASONS OR THIS TEST TESTS NOTHING. An earlier
         * version of it gave both plans the same 900 MiB on-disk size on a phone short of storage,
         * so both refusals came back NOT_ENOUGH_FREE_STORAGE and the assertion passed whether the
         * smallest plan's reason was chosen or the largest plan's. It is written now so the two
         * answers differ: the larger plan does not fit in this handset's memory AT ALL, and the
         * smaller one fits in memory and is short of storage.
         *
         * Reporting the larger plan's DEVICE_TOO_SMALL would tell this designer their phone can
         * never do this, when in fact freeing 500 MB would let it.
         */
        val bigger = NOT_A_REAL_MODEL.copy(
            modelId = "also-not-real-bigger",
            peakRssBytes = 3_500L * mib,
        )
        val noRoom = fourGigClassPhone.copy(
            totalRamBytes = 3_700L * mib,
            availableRamBytes = 3_000L * mib,
            freeStorageBytes = 1_500L * mib,
        )
        // The larger plan needs 3,500 + 512 MiB and this phone reports 3,700 in total: a durable no.
        assertEquals(DwTierRefusal.DEVICE_TOO_SMALL, dwPlanFits(bigger, noRoom))
        // The smaller one fits in memory and is 424 MiB short of storage: a no that can be acted on.
        assertEquals(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE, dwPlanFits(NOT_A_REAL_MODEL, noRoom))

        val offer = dwBestPlan(listOf(NOT_A_REAL_MODEL, bigger), noRoom, emptyList())
        assertEquals(
            DwTierOffer.None(DwTierRefusal.NOT_ENOUGH_FREE_STORAGE),
            offer
        )
    }

    // ---------------------------------------------------------------------------------------
    // A load that failed here is evidence, and it outranks the table
    // ---------------------------------------------------------------------------------------

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
        // The reason a failure note carries the cap as well as the id: KV-cache is most of what
        // varies between the two, and a 2K run failing says nothing about a 1K one.
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
        assertTrue(online.sentence.contains("server"))
        assertTrue("the cap belongs in the sentence too", online.sentence.contains("2048"))
    }

    @Test
    fun `a failed load with no connection goes nowhere, and says nothing was lost`() {
        // The outcome that must not be silent. "Failing the job silently" is the thing plan §2.1
        // names; a designer who is told nothing concludes the recording went with it.
        val note = DwLoadFailureNote(DwAiTier.TIER_2, "not-a-real-model", 2048, "OOM")
        val offline = dwFallbackAfterLoadFailure(note, DwConnection.NONE)
        assertNull(offline.goesTo)
        assertTrue(offline.sentence.contains("Nothing has been lost"))
    }

    // ---------------------------------------------------------------------------------------
    // Re-probe, do not cache
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a reading goes stale, and a reading from a mixed clock is stale immediately`() {
        assertFalse(dwProbeIsStale(takenAtElapsedMs = 1_000L, nowElapsedMs = 1_000L))
        assertFalse(dwProbeIsStale(1_000L, 1_000L + DW_PROBE_FRESH_FOR_MS))
        assertTrue(dwProbeIsStale(1_000L, 1_000L + DW_PROBE_FRESH_FOR_MS + 1L))
        // elapsedRealtime is monotonic, so a negative age means the two numbers came from different
        // clocks. A mixed-clock age is not an age, and the safe reading of it is "re-probe".
        assertTrue(dwProbeIsStale(takenAtElapsedMs = 5_000L, nowElapsedMs = 1_000L))
    }

    // ---------------------------------------------------------------------------------------
    // When a job may start — the capture rule above all
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Tier 2 never starts while capture is open, whatever else is true`() {
        // The rule whose violation loses data: a summariser that pushes the process over the
        // low-memory line mid-recording takes the artisan's sentence with it, and the artisan does
        // not say it again. Asserted against an offer that is otherwise perfect.
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
        // API < 29 has no way to ask. Barring such a handset for ever from a capability because it
        // could not describe its own temperature would turn a missing answer into a verdict.
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
        // A designer in a courtyard has no socket. A capability that only ever works at the guest
        // house is not the offline capability any of this is for.
        assertNotNull(dwTier2PowerAdvice(charging = false))
        assertNull(dwTier2PowerAdvice(charging = true))
        assertNull("an unread BatteryManager must produce silence, not a hedge", dwTier2PowerAdvice(null))
    }

    // ---------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unmeasured byte count is the word unmeasured, never zero`() {
        assertEquals("unmeasured", dwBytesLabel(null))
        // THE BASE IS 1000, SO THE LETTERS MEAN WHAT THEY SAY. This is pinned rather than left to
        // the implementation because the figure that matters most is a download size standing next
        // to a prepaid data bundle, and a bundle is sold in decimal gigabytes: an earlier draft
        // divided by 1024 and printed a 3,000,000,000-byte artifact as "2.8 GB", understating the
        // bill in the one direction a size beside somebody's data allowance must not be wrong in.
        assertEquals("3.0 GB", dwBytesLabel(3_000_000_000L))
        assertEquals("500 MB", dwBytesLabel(500_000_000L))
        // And a figure that came off the phone in binary units prints as the decimal number it is.
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
        // And on a handset that answered, the numbers themselves are printed — a designer has to be
        // able to read them down a phone line when something goes wrong.
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
            assertTrue("and a long enough one to say what to do: $sentence", sentence.length > 60)
        }
    }

    @Test
    fun `an offer states the model, what one run is, the real size and where the number came from`() {
        // "Show the real size" — the language-pack screen refuses to print one because the platform
        // reports none; our own models have a known size, so this screen must state it before the
        // tap. And what one RUN is travels with the model everywhere the model is named.
        val offer = DwTierOffer.Available(NOT_A_REAL_MODEL, headroomBytes = 800L * mib)
        val sentence = dwTierOfferSentence(DwAiTier.TIER_2, offer)
        assertTrue(sentence.contains(NOT_A_REAL_MODEL.modelId))
        /*
         * IT USED TO ASSERT `sentence.contains("2048")` — the cap, as a bare number. That was the
         * right rule spelled in the wrong units: what a sentence naming a model may not omit is the
         * ENVELOPE ITS MEMORY FIGURE WAS MEASURED OVER, and for a CTC speech model that is seconds of
         * audio rather than tokens. `runBound` is that envelope for every model family, so the
         * assertion is on it — and the fixture's own bound still names its 2,048-token cap, so the
         * decoder case is covered by the same line.
         */
        assertTrue(
            "the envelope one run was measured over is not optional in a sentence naming a model",
            sentence.contains(NOT_A_REAL_MODEL.runBound)
        )
        assertTrue("and for a decoder that envelope is still its cap", sentence.contains("2,048-token"))
        // 900 MiB and 1,200 MiB, printed in the decimal units a data bundle is sold in.
        assertTrue(sentence.contains("944 MB"))
        assertTrue(sentence.contains("1.3 GB"))
        assertTrue(sentence.contains(NOT_A_REAL_MODEL.measuredOn))
        assertTrue(
            "nothing downloads by itself, and the sentence says so",
            sentence.contains("Nothing is fetched unless you ask for it")
        )
    }

    @Test
    fun `a model that dies when the app is backgrounded says so in the offer`() {
        // A designer takes a photograph mid-summary. If that kills the process, the summary and
        // possibly the draft go with it, so this cannot be a footnote in a measurement document.
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

    // ---------------------------------------------------------------------------------------
    // The safeguard that makes device-dependent tiering permissible at all
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the tier names are the wire values the provenance half already writes down`() {
        // `AiTier` in backend/app/services/ai_layers.py and the DwAiTier Postgres enum both spell
        // them this way, and DwAiLayer.tier is NOT NULL. A rename here has to fail on a desktop JVM
        // rather than as a 422 from a district town — device-dependent tiering and provenance ship
        // together or not at all, and this is the seam between the two halves.
        assertEquals(listOf("TIER_1", "TIER_2", "TIER_3"), DwAiTier.entries.map { it.name })
        assertEquals(1, DwAiTier.TIER_1.number)
        assertEquals(3, DwAiTier.TIER_3.number)
        assertEquals("on this phone", DwAiTier.TIER_2.where)
        assertEquals("on the server", DwAiTier.TIER_3.where)
    }
}
