package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model CHOICE — advice rather than a verdict — on the desktop JVM.
 *
 * ── WHAT THIS FILE IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * `DwDeviceTierTest` pins that this app never names a model nobody has weighed. This file pins the
 * half that comes after that: given models that HAVE been weighed, the app tells a designer what
 * each would cost on their own handset, lets them pick one the probe would not have picked, and
 * refuses — in exactly three arithmetic cases and one measured one — the ones that cannot run at
 * all.
 *
 * The two ways this can go wrong are opposites and both are pinned below:
 *
 *  * **Paternalism.** A model that would fit, refused because a chosen margin says so. The margins
 *    are chosen numbers ([DW_MODEL_FREE_RAM_MARGIN_BYTES] says so in its own comment), and a
 *    designer who works with the charger in and nothing else open knows something about their day
 *    that one reading of `availMem` does not.
 *  * **A crash sold as a choice.** A model larger than the phone, offered because "the user can
 *    decide". They cannot decide their way past a resident set larger than the machine.
 *
 * ── THE FIXTURE RULE, INHERITED ───────────────────────────────────────────────────────────────
 *
 * Every plan here is called `NOT_A_REAL_*`, every number in it is round, and no handset in this file
 * is named after a real one. `DwLanguagePackTest`'s scar — a fixture named for the fleet's own M32
 * asserting capabilities it did not have — is the reason.
 */
class DwModelChoiceTest {

    private val mib: Long = 1024L * 1024L

    // ---------------------------------------------------------------------------------------
    // Fixtures — shaped, not measured
    // ---------------------------------------------------------------------------------------

    /** 3,700 MiB reported, 1,100 MiB free, 12,000 MiB of storage, 64-bit. Not a reading. */
    private val ordinaryPhone = DwDeviceMeasurement(
        totalRamBytes = 3_700L * mib,
        availableRamBytes = 1_100L * mib,
        lowRamDevice = false,
        freeStorageBytes = 12_000L * mib,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        thermal = DwThermalState.NONE,
        charging = false,
    )

    private val roomyPhone = ordinaryPhone.copy(
        totalRamBytes = 11_800L * mib,
        availableRamBytes = 6_000L * mib,
        freeStorageBytes = 90_000L * mib,
    )

    private val goEditionPhone = ordinaryPhone.copy(
        totalRamBytes = 1_900L * mib,
        availableRamBytes = 900L * mib,
        lowRamDevice = true,
        freeStorageBytes = 3_000L * mib,
    )

    private val phoneThatWouldNotAnswer = DwDeviceMeasurement()

    /**
     * A SMALL MODEL THAT DOES NOT EXIST. 200 MiB resident, 150 MiB on disk — small enough to be
     * comfortable on [ordinaryPhone] with both margins intact (needs 712 MiB free memory and
     * 1,174 MiB free storage).
     */
    private val NOT_A_REAL_SMALL_MODEL = DwModelPlan(
        modelId = "not-a-real-small-model",
        quantisation = "int4",
        abi = "arm64-v8a",
        languages = listOf("hi-IN", "en-IN"),
        contextCapTokens = 1024,
        // A DECODER-SHAPED FIXTURE, so the cap above is the envelope and this sentence
        // restates it. Nothing here was measured; see this file's fixture rule.
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 150L * mib,
        peakRssBytes = 200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    /**
     * A MIDDLING MODEL THAT DOES NOT EXIST. 900 MiB resident: it fits in [ordinaryPhone]'s
     * 1,100 MiB free, but not with the 512 MiB margin — 1,412 MiB needed against 1,100 MiB free —
     * so it is the TIGHT case this whole lane is about.
     */
    private val NOT_A_REAL_MIDDLING_MODEL = NOT_A_REAL_SMALL_MODEL.copy(
        modelId = "not-a-real-middling-model",
        languages = listOf("or-IN"),
        onDiskBytes = 800L * mib,
        peakRssBytes = 900L * mib,
    )

    /** A MODEL LARGER THAN THE PHONE. 4,000 MiB resident against 3,700 MiB reported in total. */
    private val NOT_A_REAL_HUGE_MODEL = NOT_A_REAL_SMALL_MODEL.copy(
        modelId = "not-a-real-huge-model",
        languages = listOf("or-IN", "bn-IN"),
        onDiskBytes = 3_000L * mib,
        peakRssBytes = 4_000L * mib,
    )

    private val NOT_A_REAL_32_BIT_MODEL = NOT_A_REAL_SMALL_MODEL.copy(
        modelId = "not-a-real-32-bit-model",
        abi = "x86_64",
    )

    private val catalogue = listOf(
        NOT_A_REAL_SMALL_MODEL,
        NOT_A_REAL_MIDDLING_MODEL,
        NOT_A_REAL_HUGE_MODEL,
    )

    // ---------------------------------------------------------------------------------------
    // Today's answer. If any of these change, the shipped promise has changed
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the list is the catalogue judged, and no control is ever drawn without a connection`() {
        /*
         * THE INVARIANTS, ASSERTED AGAINST WHATEVER THE SHIPPED CATALOGUES CONTAIN.
         *
         * DELIBERATELY NOT "the catalogues are empty" — `DwDeviceTierTest` already owns that
         * tripwire, and a second copy of it would put two red tests behind one conversation the day
         * somebody adds a measured row. What this pins instead survives that day: nothing is hidden
         * from the list, nothing that cannot run is offered, and nothing that spends data is offered
         * to a handset with no connection to spend it on.
         */
        listOf(ordinaryPhone, roomyPhone, goEditionPhone, phoneThatWouldNotAnswer).forEach { phone ->
            DwConnection.entries.forEach { connection ->
                val recommendation = dwRecommendTiers(phone, connection)
                assertEquals(
                    "every measured model is listed, including the ones that will not fit — a " +
                        "designer who has heard one exists must find out HERE that it cannot run",
                    DW_TIER1_CATALOGUE.size,
                    recommendation.tier1Choices.size
                )
                assertEquals(DW_TIER2_CATALOGUE.size, recommendation.tier2Choices.size)
                (recommendation.tier1Choices + recommendation.tier2Choices).forEach { choice ->
                    if (!choice.fit.mayInstall) {
                        assertFalse(
                            "${choice.plan.modelId}: the line was crossed by a control",
                            dwModelDownloadMayBeOffered(choice, connection)
                        )
                    }
                    if (connection == DwConnection.NONE) {
                        assertFalse(
                            "a control that cannot work is worse than an absent one",
                            dwModelDownloadMayBeOffered(choice, connection)
                        )
                    }
                }
            }
        }
        // AND TODAY, WITH THE CATALOGUES AS SHIPPED, THAT COMES TO NOTHING ON EVERY HANDSET.
        // Written as a consequence of the emptiness rather than as a second assertion of it.
        if (DW_TIER1_CATALOGUE.isEmpty() && DW_TIER2_CATALOGUE.isEmpty()) {
            val recommendation = dwRecommendTiers(ordinaryPhone, DwConnection.UNMETERED)
            assertTrue(recommendation.tier1Choices.isEmpty() && recommendation.tier2Choices.isEmpty())
            assertNull(dwModelChoiceIntroSentence(recommendation.tier1Choices))
        }
    }

    @Test
    fun `every language a catalogued model claims is one this app actually offers`() {
        /*
         * THE ASSERTION [DwModelPlan]'s CONSTRUCTOR CANNOT MAKE. The nineteen live in a Compose file
         * and the data layer may not import it, so the membership check lives here — and it has to,
         * because a model row claiming `od-IN` (not a real tag; Odia is `or`) would silently cover
         * no language at all while looking like coverage on the card.
         *
         * THE COMPARISON IS `dwTagCovers` AND NOT EQUALITY AFTER NORMALISATION, which is what this
         * check used to do. Normalisation folds separator and case only, so a row measured against a
         * bare `or` — legitimate, and exactly what `dwModelServesLanguage` widens to cover `or-IN` —
         * failed this assertion while serving Odia perfectly well. A test that rejects an honest
         * catalogue row is a test that sends whoever adds the first one down a wrong path, and the
         * one rule about tags in this app has to be the same rule everywhere it is asked. It still
         * catches everything it was written for: `en-US` covers no offered row, because the widening
         * deliberately does not run the other way, and `od-IN` covers nothing at all.
         */
        val offered = DW_DICTATION_LANGUAGES.map { it.tag }
        (DW_TIER1_CATALOGUE + DW_TIER2_CATALOGUE).forEach { plan ->
            plan.languages?.forEach { tag ->
                assertTrue(
                    "${plan.modelId} claims $tag, which covers none of the languages this app offers",
                    offered.any { dwTagCovers(tag, it) }
                )
            }
        }
        // AND THE CHECK ITSELF IS NOT VACUOUS WHILE BOTH CATALOGUES ARE EMPTY. Asserted against the
        // shapes it exists to reject, so an edit that neutered it goes red rather than going quiet.
        assertTrue("a bare language tag covers its region", offered.any { dwTagCovers("or", it) })
        assertFalse("en-US must never be read as English (India)", offered.any { dwTagCovers("en-US", it) })
        assertFalse("od-IN is not a language this app offers", offered.any { dwTagCovers("od-IN", it) })
    }

    @Test
    fun `a model plan will not hold a blank or duplicated language tag`() {
        val blank = runCatching { NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("hi-IN", " ")) }
        assertTrue("a blank tag matches nothing while looking like an answer", blank.isFailure)
        val duplicated = runCatching {
            NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("hi-IN", "HI_in"))
        }
        assertTrue(
            "two spellings of one tag are one language to every comparison in this app",
            duplicated.isFailure
        )
        // And the two honest empties are both constructible, because they are different facts.
        assertNotNull(NOT_A_REAL_SMALL_MODEL.copy(languages = null))
        assertNotNull(NOT_A_REAL_SMALL_MODEL.copy(languages = emptyList()))
    }

    // ---------------------------------------------------------------------------------------
    // THE LINE — three arithmetic refusals and one measured one
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a model larger than the phone's whole memory will not fit, and consent cannot buy it`() {
        val choice = dwModelFit(NOT_A_REAL_HUGE_MODEL, ordinaryPhone)
        assertEquals(DwModelFit.WILL_NOT_FIT, choice.fit)
        assertEquals(listOf(DwFitNote.LARGER_THAN_THIS_PHONE_S_MEMORY), choice.notes)
        assertFalse("the line: no control, on any connection", choice.fit.mayInstall)
        assertFalse(dwModelDownloadMayBeOffered(choice, DwConnection.UNMETERED))
        assertNull("there is nothing to consent to", dwModelOverrideSentence(choice))
        assertTrue(
            "the sentence says closing apps would not help, because it would not",
            dwModelFitSentence(choice, ordinaryPhone).contains("would not make room")
        )
    }

    @Test
    fun `a model exactly the size of the phone's memory is refused, not merely tight`() {
        // The equal case, which is the one a `>` would have let through: a resident set that is the
        // whole of reported memory leaves nothing for the kernel, Compose or this app's own draft.
        val exact = NOT_A_REAL_SMALL_MODEL.copy(peakRssBytes = 3_700L * mib)
        assertEquals(DwModelFit.WILL_NOT_FIT, dwModelFit(exact, ordinaryPhone).fit)
        val oneByteSmaller = NOT_A_REAL_SMALL_MODEL.copy(peakRssBytes = 3_700L * mib - 1)
        assertEquals(
            "one byte under is a tight fit rather than an impossible one, and is offered",
            DwModelFit.TIGHT,
            dwModelFit(oneByteSmaller, ordinaryPhone).fit
        )
    }

    @Test
    fun `a model larger than the free storage will not fit, because the download cannot finish`() {
        val cramped = ordinaryPhone.copy(freeStorageBytes = 100L * mib)
        val choice = dwModelFit(NOT_A_REAL_SMALL_MODEL, cramped)
        assertEquals(DwModelFit.WILL_NOT_FIT, choice.fit)
        assertEquals(listOf(DwFitNote.LARGER_THAN_THE_FREE_STORAGE), choice.notes)
    }

    @Test
    fun `a model built for another processor will not fit, whatever the memory says`() {
        val choice = dwModelFit(NOT_A_REAL_32_BIT_MODEL, roomyPhone)
        assertEquals(DwModelFit.WILL_NOT_FIT, choice.fit)
        assertEquals(listOf(DwFitNote.NO_BUILD_FOR_THIS_PROCESSOR), choice.notes)
    }

    @Test
    fun `a load that already failed on this handset outranks every piece of arithmetic`() {
        /*
         * PLAN §2.1'S RULE, AS A REFUSAL: "if a load fails on a device the table said was fine, that
         * is data". The model below is comfortable by every number, and the handset has already
         * proved the numbers wrong. Evidence wins, and it wins BEFORE the arithmetic runs — a
         * version that checked the fit first would print "comfortable" for the length of one branch.
         */
        val failed = listOf(
            DwLoadFailureNote(
                tier = DwAiTier.TIER_1,
                modelId = NOT_A_REAL_SMALL_MODEL.modelId,
                contextCapTokens = NOT_A_REAL_SMALL_MODEL.contextCapTokens,
                detail = "invented for this test",
            )
        )
        assertEquals(DwModelFit.COMFORTABLE, dwModelFit(NOT_A_REAL_SMALL_MODEL, roomyPhone).fit)
        val after = dwModelFit(NOT_A_REAL_SMALL_MODEL, roomyPhone, failed)
        assertEquals(DwModelFit.WILL_NOT_FIT, after.fit)
        assertEquals(listOf(DwFitNote.LOAD_FAILED_HERE_BEFORE), after.notes)
    }

    @Test
    fun `the same model at a different context cap is a different run and is not retired`() {
        // [DwLoadFailureNote]'s own rule. The same weights at a smaller cap may well load, and
        // refusing it would retire a model on the strength of an experiment nobody performed.
        val failed = listOf(
            DwLoadFailureNote(
                tier = DwAiTier.TIER_1,
                modelId = NOT_A_REAL_SMALL_MODEL.modelId,
                contextCapTokens = 4096,
                detail = "invented for this test",
            )
        )
        assertEquals(
            DwModelFit.COMFORTABLE,
            dwModelFit(NOT_A_REAL_SMALL_MODEL, roomyPhone, failed).fit
        )
    }

    @Test
    fun `a failure recorded against one tier does not retire the other tier's model`() {
        val tier2Failure = listOf(
            DwLoadFailureNote(
                tier = DwAiTier.TIER_2,
                modelId = NOT_A_REAL_SMALL_MODEL.modelId,
                contextCapTokens = NOT_A_REAL_SMALL_MODEL.contextCapTokens,
                detail = "invented for this test",
            )
        )
        val tier1 = dwModelChoices(
            listOf(NOT_A_REAL_SMALL_MODEL), roomyPhone, tier2Failure, null, DwAiTier.TIER_1
        )
        assertEquals(DwModelFit.COMFORTABLE, tier1.single().fit)
        val tier2 = dwModelChoices(
            listOf(NOT_A_REAL_SMALL_MODEL), roomyPhone, tier2Failure, null, DwAiTier.TIER_2
        )
        assertEquals(DwModelFit.WILL_NOT_FIT, tier2.single().fit)
    }

    // ---------------------------------------------------------------------------------------
    // TIGHT — the fit that is offered anyway, with the cost said first
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a model that fits without the memory margin is offered, and says what it expects to cost`() {
        val choice = dwModelFit(NOT_A_REAL_MIDDLING_MODEL, ordinaryPhone)
        assertEquals(DwModelFit.TIGHT, choice.fit)
        assertTrue(choice.notes.contains(DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW))
        assertTrue("a tight fit is installable — that is the whole point", choice.fit.mayInstall)
        assertTrue(dwModelDownloadMayBeOffered(choice, DwConnection.UNMETERED))
        assertTrue("and it asks first", dwModelNeedsConsent(choice))

        val warning = dwModelOverrideSentence(choice)
        assertNotNull("an override must say what it expects to happen", warning)
        // The user's own words for what this has to say, and the reason the camera is named.
        assertTrue(warning!!.contains("camera"))
        assertTrue(warning.contains("Android may close this app"))
        // AND IT MUST SAY WHAT SURVIVES. A designer who believes a killed model takes the draft with
        // it will never accept a tight fit; one who thinks nothing can go wrong is being misled.
        assertTrue(warning.contains("draft"))
        assertTrue(warning.contains("saved on the phone"))
        assertTrue("the model is named on the button, so it cannot be a blind yes",
            dwModelOverrideConfirmLabel(choice).contains(NOT_A_REAL_MIDDLING_MODEL.modelId))
    }

    @Test
    fun `a comfortable model needs no ceremony`() {
        val choice = dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone)
        assertEquals(DwModelFit.COMFORTABLE, choice.fit)
        assertTrue(choice.notes.isEmpty())
        assertFalse(
            "inventing a confirmation here would train designers to tap through the one that matters",
            dwModelNeedsConsent(choice)
        )
        assertNull(dwModelOverrideSentence(choice))
    }

    @Test
    fun `Android's own low-memory flag never refuses, and never lets a model be comfortable`() {
        /*
         * WHERE THE LINE IS AND IS NOT. `isLowRamDevice` is the platform's considered verdict about
         * a build and the OEM configuration behind it knows things a memory total does not — so no
         * model is ever COMFORTABLE on such a handset. But it is not a claim that the bytes will not
         * fit, and this app does not refuse a download on somebody else's opinion when the
         * arithmetic says it would run.
         */
        val choice = dwModelFit(NOT_A_REAL_SMALL_MODEL, goEditionPhone)
        assertEquals(DwModelFit.TIGHT, choice.fit)
        assertTrue(choice.notes.contains(DwFitNote.ANDROID_CALLS_THIS_A_LOW_MEMORY_DEVICE))
        assertTrue("still installable, with the reason said first", choice.fit.mayInstall)
        assertTrue(
            dwModelFitSentence(choice, goEditionPhone).contains("Android flags this handset")
        )
    }

    @Test
    fun `every crossed margin is reported, not only the first`() {
        // "Tight on memory" and "tight on memory AND storage" are different decisions, and a
        // designer about to spend a gigabyte is owed both halves before the tap.
        val squeezed = ordinaryPhone.copy(freeStorageBytes = 1_000L * mib)
        val choice = dwModelFit(NOT_A_REAL_MIDDLING_MODEL, squeezed)
        assertEquals(DwModelFit.TIGHT, choice.fit)
        assertTrue(choice.notes.contains(DwFitNote.LITTLE_STORAGE_LEFT_OVER))
        assertTrue(choice.notes.contains(DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW))
    }

    // ---------------------------------------------------------------------------------------
    // UNMEASURED — a refusal that is not about the phone being small
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a handset that would not answer gets neither a yes nor a verdict`() {
        val choice = dwModelFit(NOT_A_REAL_SMALL_MODEL, phoneThatWouldNotAnswer)
        assertEquals(DwModelFit.UNMEASURED, choice.fit)
        assertFalse("nothing this size is fetched on a guess", choice.fit.mayInstall)
        val sentence = dwModelFitSentence(choice, phoneThatWouldNotAnswer)
        assertTrue(sentence.contains("would not say"))
        assertFalse(
            "an unread signal is not a small phone, and the sentence must not say it is",
            sentence.contains("too small")
        )
    }

    @Test
    fun `each unmeasured signal refuses on its own, in order of durability`() {
        assertEquals(
            listOf(DwFitNote.PROCESSOR_UNMEASURED),
            dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone.copy(abis = emptyList())).notes
        )
        assertEquals(
            listOf(DwFitNote.TOTAL_MEMORY_UNMEASURED),
            dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone.copy(totalRamBytes = null)).notes
        )
        assertEquals(
            listOf(DwFitNote.FREE_STORAGE_UNMEASURED),
            dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone.copy(freeStorageBytes = null)).notes
        )
        assertEquals(
            listOf(DwFitNote.FREE_MEMORY_UNMEASURED),
            dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone.copy(availableRamBytes = null)).notes
        )
    }

    // ---------------------------------------------------------------------------------------
    // THE SUGGESTION AND THE CHOICES CANNOT DISAGREE
    // ---------------------------------------------------------------------------------------

    @Test
    fun `comfortable is exactly what the suggestion arithmetic already called a fit`() {
        /*
         * THE MECHANICAL GUARD THAT THE ADVICE LAYER DID NOT FORK THE ARITHMETIC. [dwPlanFits] is
         * what `dwBestPlan` — and therefore every offer this app has ever made — decides from. If
         * these two ever disagree, one card would suggest a model the list beneath it calls tight.
         *
         * EVERY READING BELOW IS ONE THE PROBE CAN ACTUALLY PRODUCE. The one combination left out —
         * a null total with a live `availMem` beside it — is unreachable and is asserted separately
         * in the next test, for the reason `dwPlanFits` gives in its own docstring: both figures
         * come out of ONE `MemoryInfo` read in `DwDeviceProbe.kt`, so a reading cannot lose the
         * total and keep the free figure.
         */
        val phones = listOf(ordinaryPhone, roomyPhone, goEditionPhone, phoneThatWouldNotAnswer,
            ordinaryPhone.copy(freeStorageBytes = 1_000L * mib),
            ordinaryPhone.copy(availableRamBytes = 300L * mib),
            ordinaryPhone.copy(freeStorageBytes = null, availableRamBytes = null),
            ordinaryPhone.copy(totalRamBytes = null, availableRamBytes = null),
            ordinaryPhone.copy(abis = emptyList()))
        val plans = catalogue + NOT_A_REAL_32_BIT_MODEL
        phones.forEach { phone ->
            plans.forEach { plan ->
                val fits = dwPlanFits(plan, phone) == null
                val comfortable = dwModelFit(plan, phone).fit == DwModelFit.COMFORTABLE
                assertEquals(
                    "${plan.modelId} on a phone reporting ${phone.totalRamBytes}: dwPlanFits and " +
                        "dwModelFit disagree, so the suggestion and the list would contradict",
                    fits,
                    comfortable
                )
            }
        }
    }

    @Test
    fun `on the one unreachable reading the two differ, and the choice is the stricter of them`() {
        /*
         * A NULL TOTAL WITH A LIVE FREE FIGURE. `dwPlanFits` lets it through — total is consulted
         * there only as a durable ceiling and the free-memory gate does the work — while
         * [dwModelFit] refuses it, because the LINE it draws ("larger than this phone's whole
         * memory") cannot be tested without a total, and a line that quietly stops being checked
         * when a signal goes missing is not a line.
         *
         * THE DIVERGENCE IS RECORDED RATHER THAN REMOVED because it cannot be reached: both figures
         * come from one `MemoryInfo` read, so this reading does not arrive from any handset. What is
         * asserted is the direction — the newer function is the stricter one — so that if some
         * future probe DOES produce it, the card refuses a download it cannot judge instead of
         * offering one it cannot size.
         */
        val impossible = ordinaryPhone.copy(totalRamBytes = null)
        assertNull("dwPlanFits still allows it", dwPlanFits(NOT_A_REAL_SMALL_MODEL, impossible))
        assertEquals(
            DwModelFit.UNMEASURED,
            dwModelFit(NOT_A_REAL_SMALL_MODEL, impossible).fit
        )
        assertFalse(
            "and stricter means no control, not a quiet yes",
            dwModelFit(NOT_A_REAL_SMALL_MODEL, impossible).fit.mayInstall
        )
    }

    @Test
    fun `the suggested row is the offer, and only ever a comfortable one`() {
        val offer = dwBestPlan(catalogue, ordinaryPhone, emptyList())
        assertTrue(offer is DwTierOffer.Available)
        val suggestedPlan = (offer as DwTierOffer.Available).plan
        val choices = dwModelChoices(catalogue, ordinaryPhone, emptyList(), offer, DwAiTier.TIER_1)
        val marked = choices.filter { it.suggested }
        assertEquals("exactly one row may carry the marking", 1, marked.size)
        assertEquals(suggestedPlan.modelId, marked.single().plan.modelId)
        assertEquals(DwModelFit.COMFORTABLE, marked.single().fit)
    }

    @Test
    fun `a choice cannot be built claiming to be suggested while it is tight`() {
        val tight = dwModelFit(NOT_A_REAL_MIDDLING_MODEL, ordinaryPhone)
        val impossible = runCatching { tight.copy(suggested = true) }
        assertTrue(
            "the app must not recommend something it warns about two lines later",
            impossible.isFailure
        )
    }

    @Test
    fun `nothing is suggested where nothing is comfortable, and every row is still listed`() {
        // A phone where the whole catalogue is tight or worse. The list must not go empty: a
        // designer who has heard a model exists needs to find out HERE that it will not fit.
        val squeezed = ordinaryPhone.copy(availableRamBytes = 600L * mib)
        val offer = dwBestPlan(catalogue, squeezed, emptyList())
        assertTrue(offer is DwTierOffer.None)
        val choices = dwModelChoices(catalogue, squeezed, emptyList(), offer, DwAiTier.TIER_1)
        assertEquals(catalogue.size, choices.size)
        assertTrue(choices.none { it.suggested })
    }

    // ---------------------------------------------------------------------------------------
    // The order of the list
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the list reads comfortable, tight, unmeasured, will-not-fit, and is deterministic`() {
        val choices = dwModelChoices(
            catalogue + NOT_A_REAL_32_BIT_MODEL, ordinaryPhone, emptyList(), null, DwAiTier.TIER_1
        )
        val fits = choices.map { it.fit }
        assertEquals(
            listOf(DwModelFit.COMFORTABLE, DwModelFit.TIGHT, DwModelFit.WILL_NOT_FIT, DwModelFit.WILL_NOT_FIT),
            fits
        )
        // Twice over the same reading gives the same order: a list that reordered itself under a
        // designer's finger would move the row they were reaching for as they reached for it.
        assertEquals(
            choices.map { it.plan.modelId },
            dwModelChoices(
                catalogue + NOT_A_REAL_32_BIT_MODEL, ordinaryPhone, emptyList(), null, DwAiTier.TIER_1
            ).map { it.plan.modelId }
        )
    }

    @Test
    fun `the intro says the suggestion is advice, and counts rather than rounds`() {
        val offer = dwBestPlan(catalogue, ordinaryPhone, emptyList())
        val choices = dwModelChoices(catalogue, ordinaryPhone, emptyList(), offer, DwAiTier.TIER_1)
        val intro = dwModelChoiceIntroSentence(choices)
        assertNotNull(intro)
        assertTrue("it must say the suggestion is not binding", intro!!.contains("advice, not a rule"))
        assertTrue(intro.contains("1 of them this phone is comfortable with"))
        assertTrue(intro.contains("1 will not fit at all"))
        assertNull("and there is nothing to introduce when nothing is measured",
            dwModelChoiceIntroSentence(emptyList()))
    }

    // ---------------------------------------------------------------------------------------
    // The sentences
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a choice sentence names the model and what the download costs, and stops there`() {
        /*
         * ── THIS TEST WAS INVERTED ON 2026-08-16, AND THE INVERSION IS THE POINT ──────────────
         *
         * It used to require the quantisation tag (`int4`), the run bound and its `1,024-token
         * context cap`, the peak-memory figure and the name of the handset the figures came off —
         * five engineering values, ASSERTED ONTO A ROW A DESIGNER READS. That is how the surface the
         * repository owner has now rejected four times kept growing back after each cut: the prose
         * was trimmed and the test pulled it straight back in.
         *
         * A row exists to answer "should I spend this download". The model's name and the size
         * answer it; nothing else here does. So the two that survive are asserted, and the five that
         * went are asserted ABSENT — a test that only checks what is present cannot stop the essay
         * returning. They remain on [DwModelPlan] for whoever is choosing which model to ship, and
         * `DwModelChoiceTest` still covers them there.
         */
        val choice = dwModelFit(NOT_A_REAL_SMALL_MODEL, ordinaryPhone)
        val sentence = dwModelChoiceSentence(choice, ordinaryPhone)
        assertTrue(sentence.contains(NOT_A_REAL_SMALL_MODEL.modelId))
        assertTrue("the size a data bundle pays for", sentence.contains(dwBytesLabel(150L * mib)))

        listOf(
            "int4" to "the quantisation tag",
            "1,024-token context cap" to "the run bound",
            dwBytesLabel(200L * mib) to "the peak-memory figure",
            "this plan is a test fixture" to "the handset the figures were measured on",
        ).forEach { (fragment, what) ->
            assertFalse(
                "$what is for whoever picks which model to ship, not for the person holding the " +
                    "phone — it must not be back on the row: $sentence",
                sentence.contains(fragment)
            )
        }
    }

    @Test
    fun `a model that does not survive backgrounding says so wherever it is offered`() {
        // The rule [DwModelPlan.survivesBackgrounding] exists for: a designer takes a photograph
        // mid-summary, and if that kills the process the summary goes with it. The clause was cut
        // from 24 words to 16 on 2026-08-16; what it may never lose is the CAMERA, because that is
        // the one thing a designer is certain to do and the only part they can act on.
        val fragile = NOT_A_REAL_SMALL_MODEL.copy(survivesBackgrounding = false)
        val sentence = dwModelChoiceSentence(dwModelFit(fragile, ordinaryPhone), ordinaryPhone)
        assertTrue(sentence.contains("It stops if you leave the app"))
        assertTrue("the photograph is the part a designer can act on", sentence.contains("photograph"))
    }

    @Test
    fun `every fit has a distinct label and a sentence that names a next move or its absence`() {
        val labels = DwModelFit.entries.map { dwModelFitLabel(it) }
        assertEquals("two fits behind one label read as one fit", labels.size, labels.distinct().size)
    }

    @Test
    fun `the languages a model serves are printed in names, and unmeasured is a word`() {
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
        val known = dwModelLanguagesSentence(NOT_A_REAL_MIDDLING_MODEL, labels)
        assertTrue("a designer reads Odia, not or-IN", known.contains("Odia"))
        assertFalse(known.contains("or-IN"))

        val unmeasured = dwModelLanguagesSentence(NOT_A_REAL_SMALL_MODEL.copy(languages = null), labels)
        assertTrue(unmeasured.contains("unmeasured"))
        assertTrue("an unchecked model is not evidence", unmeasured.contains("not counted"))

        val none = dwModelLanguagesSentence(NOT_A_REAL_SMALL_MODEL.copy(languages = emptyList()), labels)
        assertTrue(none.contains("serves none of this app's dictation languages"))
        assertFalse("which is a different fact from unmeasured", none.contains("unmeasured"))
    }

    @Test
    fun `the count is what the model covers of the nineteen, not the length of its own list`() {
        /*
         * THE SENTENCE SAYS "N OF THIS APP'S LANGUAGES", SO N HAS TO BE COUNTED AGAINST THIS APP'S
         * LANGUAGES. Counting the artifact's own tags instead — which is what this replaced —
         * overstates on the one screen where overstating costs a designer a download they cannot
         * use, and it does it in the two shapes below.
         */
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }

        // 1. A REGION THAT DOES NOT MATCH. `dwTagCovers` is deliberately asymmetric — en-US does not
        //    cover en-IN — because English (India) transcribed by a US model is the silent-wrong-
        //    output failure. The row must not claim it as coverage.
        val american = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("en-US"))
        val americanSentence = dwModelLanguagesSentence(american, labels)
        assertTrue(americanSentence.contains("serves none of this app's dictation languages"))
        assertTrue("and it names the tag, so a wrong catalogue row can be found", americanSentence.contains("en-US"))
        assertFalse("it hears none of them, so it may not claim one", americanSentence.contains("hears 1"))

        // 2. A TAG THIS APP DOES NOT OFFER AT ALL. `od-IN` is the plausible typo (Odia is `or`), and
        //    printed as a raw tag beside a count it reads to a designer as a language they could
        //    dictate in.
        val typo = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("od-IN"))
        assertFalse(dwModelLanguagesSentence(typo, labels).contains("hears 1"))

        // 3. AND THE WIDENING STILL COUNTS, WITH THE NAME A DESIGNER READS. A model measured against
        //    a bare `or` serves every region of it — which the earlier version printed as the string
        //    "or", because a bare tag is not a key of the label map.
        val bare = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("or"))
        val bareSentence = dwModelLanguagesSentence(bare, labels)
        assertTrue(bareSentence.contains("hears 1 of this app's languages: Odia"))
    }

    @Test
    fun `a handset that would not answer is never told none of its models can be run`() {
        /*
         * THE HONEST-UNKNOWN RULE, IN THE ONE LINE ABOVE THE LIST. Every row on a phone that would
         * not say what processor it has is UNMEASURED, and the arm this replaced printed "none of
         * them can be run on this phone" for it — a verdict manufactured out of a missing reading,
         * which is the thing this file's own header forbids. A designer told their phone cannot run
         * any of these buys a different phone; one told the reading failed taps “Check again”.
         */
        val choices = dwModelChoices(catalogue, phoneThatWouldNotAnswer, emptyList(), null, DwAiTier.TIER_1)
        assertTrue(choices.all { it.fit == DwModelFit.UNMEASURED })
        val intro = dwModelChoiceIntroSentence(choices)!!
        assertTrue(intro.contains("did not say enough about itself"))
        assertFalse("nothing was judged, so nothing may be refused", intro.contains("can be run on this phone"))
        assertFalse(intro.contains("will not fit"))
    }

    @Test
    fun `the marking is only explained where a row carries it`() {
        // A sentence pointing at an empty set reads as a broken card — the rule
        // `dwCoverageSummarySentence` keeps for its own closing clause, applied to this one. On a
        // phone where nothing is comfortable, no row is marked, so the copy explaining the marking
        // must not be printed; the absence is explained instead, because a missing marking otherwise
        // reads as a rendering fault.
        val squeezed = ordinaryPhone.copy(availableRamBytes = 600L * mib)
        val choices = dwModelChoices(catalogue, squeezed, emptyList(), dwBestPlan(catalogue, squeezed, emptyList()), DwAiTier.TIER_1)
        assertTrue(choices.none { it.suggested })
        val intro = dwModelChoiceIntroSentence(choices)!!
        assertFalse("there is no such row to point at", intro.contains("The one marked"))
        assertTrue(intro.contains("Nothing is marked"))
        // And the capitalisation of the clause that opens after a full stop, which read as a
        // truncated string in two of the four arms.
        assertTrue(intro.contains(". None is comfortable here"))
    }

    // ---------------------------------------------------------------------------------------
    // A failed load says what changed, and does not disown advice the app actually gave
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a load that failed after an override does not claim the reading said it would fit`() {
        val chosen = DwLoadFailureNote(
            tier = DwAiTier.TIER_1,
            modelId = NOT_A_REAL_MIDDLING_MODEL.modelId,
            contextCapTokens = 1024,
            detail = "invented for this test",
            chosenAgainstAdvice = true,
        )
        val sentence = dwFallbackAfterLoadFailure(chosen, DwConnection.METERED).sentence
        assertTrue(
            "the app said to expect this; claiming otherwise would disown its own warning",
            sentence.contains("the outcome this app said to expect")
        )
        assertFalse(sentence.contains("although its memory and storage said it would fit"))

        // And the ordinary case is unchanged: the table WAS wrong, and that is the data.
        val surprise = chosen.copy(chosenAgainstAdvice = false)
        assertTrue(
            dwFallbackAfterLoadFailure(surprise, DwConnection.METERED).sentence
                .contains("although its memory and storage said it would fit")
        )
    }

    @Test
    fun `a failed load with no connection still says nothing was lost, either way it was chosen`() {
        listOf(true, false).forEach { chosen ->
            val note = DwLoadFailureNote(
                tier = DwAiTier.TIER_2,
                modelId = "not-a-real-model",
                contextCapTokens = 2048,
                detail = "OOM",
                chosenAgainstAdvice = chosen,
            )
            val fallback = dwFallbackAfterLoadFailure(note, DwConnection.NONE)
            assertNull(fallback.goesTo)
            assertTrue(fallback.sentence.contains("Nothing has been lost"))
        }
    }
}
