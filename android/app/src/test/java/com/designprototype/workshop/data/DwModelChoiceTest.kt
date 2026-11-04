package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwModelChoiceTest {

    private val mib: Long = 1024L * 1024L

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

    private val NOT_A_REAL_SMALL_MODEL = DwModelPlan(
        modelId = "not-a-real-small-model",
        quantisation = "int4",
        abi = "arm64-v8a",
        languages = listOf("hi-IN", "en-IN"),
        contextCapTokens = 1024,
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 150L * mib,
        peakRssBytes = 200L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    private val NOT_A_REAL_MIDDLING_MODEL = NOT_A_REAL_SMALL_MODEL.copy(
        modelId = "not-a-real-middling-model",
        languages = listOf("or-IN"),
        onDiskBytes = 800L * mib,
        peakRssBytes = 900L * mib,
    )

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

    @Test
    fun `the list is the catalogue judged, and no control is ever drawn without a connection`() {
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
        if (DW_TIER1_CATALOGUE.isEmpty() && DW_TIER2_CATALOGUE.isEmpty()) {
            val recommendation = dwRecommendTiers(ordinaryPhone, DwConnection.UNMETERED)
            assertTrue(recommendation.tier1Choices.isEmpty() && recommendation.tier2Choices.isEmpty())
            assertNull(dwModelChoiceIntroSentence(recommendation.tier1Choices))
        }
    }

    @Test
    fun `every language a catalogued model claims is one this app actually offers`() {
        val offered = DW_DICTATION_LANGUAGES.map { it.tag }
        (DW_TIER1_CATALOGUE + DW_TIER2_CATALOGUE).forEach { plan ->
            plan.languages?.forEach { tag ->
                assertTrue(
                    "${plan.modelId} claims $tag, which covers none of the languages this app offers",
                    offered.any { dwTagCovers(tag, it) }
                )
            }
        }
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
        assertNotNull(NOT_A_REAL_SMALL_MODEL.copy(languages = null))
        assertNotNull(NOT_A_REAL_SMALL_MODEL.copy(languages = emptyList()))
    }

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
        assertTrue(warning!!.contains("camera"))
        assertTrue(warning.contains("Android may close this app"))
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
        val squeezed = ordinaryPhone.copy(freeStorageBytes = 1_000L * mib)
        val choice = dwModelFit(NOT_A_REAL_MIDDLING_MODEL, squeezed)
        assertEquals(DwModelFit.TIGHT, choice.fit)
        assertTrue(choice.notes.contains(DwFitNote.LITTLE_STORAGE_LEFT_OVER))
        assertTrue(choice.notes.contains(DwFitNote.LITTLE_FREE_MEMORY_RIGHT_NOW))
    }

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

    @Test
    fun `comfortable is exactly what the suggestion arithmetic already called a fit`() {
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
        val squeezed = ordinaryPhone.copy(availableRamBytes = 600L * mib)
        val offer = dwBestPlan(catalogue, squeezed, emptyList())
        assertTrue(offer is DwTierOffer.None)
        val choices = dwModelChoices(catalogue, squeezed, emptyList(), offer, DwAiTier.TIER_1)
        assertEquals(catalogue.size, choices.size)
        assertTrue(choices.none { it.suggested })
    }

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

    @Test
    fun `a choice sentence names the model and what the download costs, and stops there`() {
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
        assertTrue(unmeasured.contains("not used for any"))
        assertTrue("an unchecked model is not evidence", unmeasured.contains("not listed"))

        val none = dwModelLanguagesSentence(NOT_A_REAL_SMALL_MODEL.copy(languages = emptyList()), labels)
        assertTrue(none.contains("serves none of this app's dictation languages"))
        assertFalse("which is a different fact from unmeasured", none.contains("unmeasured"))
    }

    @Test
    fun `the count is what the model covers of the nineteen, not the length of its own list`() {
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }

        val american = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("en-US"))
        val americanSentence = dwModelLanguagesSentence(american, labels)
        assertTrue(americanSentence.contains("serves none of this app's dictation languages"))
        assertTrue("and it names the tag, so a wrong catalogue row can be found", americanSentence.contains("en-US"))
        assertFalse("it hears none of them, so it may not claim one", americanSentence.contains("hears 1"))

        val typo = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("od-IN"))
        assertFalse(dwModelLanguagesSentence(typo, labels).contains("hears 1"))

        val bare = NOT_A_REAL_SMALL_MODEL.copy(languages = listOf("or"))
        val bareSentence = dwModelLanguagesSentence(bare, labels)
        assertTrue(bareSentence.contains("hears 1 of this app's languages: Odia"))
    }

    @Test
    fun `a handset that would not answer is never told none of its models can be run`() {
        val choices = dwModelChoices(catalogue, phoneThatWouldNotAnswer, emptyList(), null, DwAiTier.TIER_1)
        assertTrue(choices.all { it.fit == DwModelFit.UNMEASURED })
        val intro = dwModelChoiceIntroSentence(choices)!!
        assertTrue(intro.contains("did not say enough about itself"))
        assertFalse("nothing was judged, so nothing may be refused", intro.contains("can be run on this phone"))
        assertFalse(intro.contains("will not fit"))
    }

    @Test
    fun `the marking is only explained where a row carries it`() {
        val squeezed = ordinaryPhone.copy(availableRamBytes = 600L * mib)
        val choices = dwModelChoices(catalogue, squeezed, emptyList(), dwBestPlan(catalogue, squeezed, emptyList()), DwAiTier.TIER_1)
        assertTrue(choices.none { it.suggested })
        val intro = dwModelChoiceIntroSentence(choices)!!
        assertFalse("there is no such row to point at", intro.contains("The one marked"))
        assertTrue(intro.contains("Nothing is marked"))
        assertTrue(intro.contains(". None is comfortable here"))
    }

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
