package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAsrModelTest {

    @Test
    fun theRowDescribesTheFileThatWasActuallyHashed() {
        val model = dwAsrModel()
        assertNotNull("This build should pin exactly one speech model.", model)
        requireNotNull(model)

        assertEquals(
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            model.modelId,
        )
        assertEquals("int8", model.quantisation)

        val graph = model.files.single { it.fileName == "model.int8.onnx" }
        assertEquals(
            "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c",
            graph.sha256,
        )
        assertEquals(365_352_120L, graph.bytes)

        val tokens = model.files.single { it.fileName == "tokens.txt" }
        assertEquals(
            "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31",
            tokens.sha256,
        )
        assertEquals(86_423L, tokens.bytes)

        assertEquals(365_438_543L, model.onDiskBytes)
    }

    @Test
    fun theLanguageNoteSeparatesWhatIsClaimedFromWhatWasMeasured() {
        val note = dwAsrModel()!!.languageNote
        assertTrue(
            "The note must attribute the 1,600-language figure to whoever claims it rather than " +
                "stating it as a property of this artifact.",
            note.contains("CLAIM"),
        )
        assertTrue(
            "The note must say the vocabulary reading is necessary and not sufficient — a model " +
                "that can spell a script has not been shown to hear the language.",
            note.contains("necessary condition") && note.contains("not a sufficient one"),
        )
        listOf("guaranteed", "supports all", "any language", "certified").forEach { word ->
            assertFalse(
                "“$word” claims more than a handset reading can support.",
                note.lowercase().contains(word),
            )
        }
    }

    @Test
    fun theEngineAbiIsChosenByTheHandsetsOwnPreferenceOrder() {
        assertEquals(
            "arm64-v8a",
            dwAsrEngineAbiFor(listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
        assertEquals("armeabi-v7a", dwAsrEngineAbiFor(listOf("armeabi-v7a", "armeabi")))
        assertEquals("armeabi-v7a", dwAsrEngineAbiFor(listOf("x86_64", "x86", "armeabi-v7a")))
        assertNull(dwAsrEngineAbiFor(listOf("x86_64", "x86")))
        assertNull(dwAsrEngineAbiFor(emptyList()))
    }

    @Test
    fun aModelFileCannotBePinnedWithoutARealDigestARealSizeAndABareName() {
        val digest = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"

        listOf("", "abc", digest.dropLast(1), digest + "0").forEach { bad ->
            runCatching { DwAsrModelFile("model.int8.onnx", bad, 1L) }
                .onSuccess { throw AssertionError("“$bad” was accepted as a SHA-256 and is not one.") }
        }
        runCatching { DwAsrModelFile("../databases/workshop.db", digest, 1L) }
            .onSuccess { throw AssertionError("A path was accepted where a bare file name is required.") }
        runCatching { DwAsrModelFile("model.int8.onnx", digest, 0L) }
            .onSuccess { throw AssertionError("A zero size was accepted; the storage gate reads it.") }

        runCatching {
            DwAsrModel(
                modelId = "x",
                quantisation = "int8",
                family = DwAsrModelFamily.OMNILINGUAL_ASR_CTC,
                files = listOf(
                    DwAsrModelFile("model.int8.onnx", digest, 1L),
                    DwAsrModelFile("tokens.txt", digest, 1L),
                    DwAsrModelFile("tokens.txt", digest, 1L),
                ),
                heads = listOf(DwAsrModelHead(null, "model.int8.onnx", "tokens.txt")),
                upstreamVersion = "x",
                provenance = "x",
                languageNote = "x",
            )
        }.onSuccess { throw AssertionError("Two files with one name were accepted.") }
    }

    @Test
    fun aHeadCannotOpenAFileTheModelDidNotPin() {
        val digest = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"
        fun model(heads: List<DwAsrModelHead>) = DwAsrModel(
            modelId = "x",
            quantisation = "int8",
            family = DwAsrModelFamily.NEMO_ENC_DEC_CTC,
            files = listOf(
                DwAsrModelFile("model-or.onnx", digest, 1L),
                DwAsrModelFile("tokens-or.txt", digest, 1L),
            ),
            heads = heads,
            upstreamVersion = "x",
            provenance = "x",
            languageNote = "x",
        )

        runCatching { model(listOf(DwAsrModelHead("or-IN", "model-or.onnx", "tokens-hi.txt"))) }
            .onSuccess { throw AssertionError("A head read an unpinned vocabulary file.") }
        runCatching { model(listOf(DwAsrModelHead("or-IN", "model-hi.onnx", "tokens-or.txt"))) }
            .onSuccess { throw AssertionError("A head opened an unpinned graph file.") }
        runCatching { model(emptyList()) }
            .onSuccess { throw AssertionError("A model with no head at all was accepted.") }
        runCatching {
            model(
                listOf(
                    DwAsrModelHead(null, "model-or.onnx", "tokens-or.txt"),
                    DwAsrModelHead(null, "model-or.onnx", "tokens-or.txt"),
                )
            )
        }.onSuccess { throw AssertionError("Two catch-all heads were accepted; headFor is then a coin toss.") }
        runCatching {
            model(
                listOf(
                    DwAsrModelHead("or-IN", "model-or.onnx", "tokens-or.txt"),
                    DwAsrModelHead("or_IN", "model-or.onnx", "tokens-or.txt"),
                )
            )
        }.onSuccess { throw AssertionError("or-IN and or_IN were accepted as two languages; they are one.") }
    }

    @Test
    fun headForPrefersTheLanguageAndRefusesRatherThanSubstituting() {
        val digest = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"
        val perLanguage = DwAsrModel(
            modelId = "x",
            quantisation = "int8",
            family = DwAsrModelFamily.NEMO_ENC_DEC_CTC,
            files = listOf(
                DwAsrModelFile("model-or.onnx", digest, 1L),
                DwAsrModelFile("tokens-or.txt", digest, 1L),
                DwAsrModelFile("model-ml.onnx", digest, 1L),
                DwAsrModelFile("tokens-ml.txt", digest, 1L),
            ),
            heads = listOf(
                DwAsrModelHead("or", "model-or.onnx", "tokens-or.txt"),
                DwAsrModelHead("ml", "model-ml.onnx", "tokens-ml.txt"),
            ),
            upstreamVersion = "x",
            provenance = "x",
            languageNote = "x",
        )
        assertEquals("model-or.onnx", perLanguage.headFor("or-IN")?.graphFileName)
        assertEquals("model-ml.onnx", perLanguage.headFor("ml-IN")?.graphFileName)
        assertNull(
            "Hindi has no head in this artifact and must resolve to nothing. Substituting the Odia " +
                "head would return a fluent transcript in the wrong script, which a stage would store.",
            perLanguage.headFor("hi-IN"),
        )

        val single = DW_ASR_MODELS.first()
        assertEquals(1, single.heads.size)
        assertNull("The pinned model's one head is the catch-all.", single.heads.first().languageTag)
        listOf("hi-IN", "or-IN", "en-IN").forEach { tag ->
            assertEquals(
                "A single-head model resolves every tag to its one graph.",
                single.heads.first().graphFileName,
                single.headFor(tag)?.graphFileName,
            )
        }
    }

    @Test
    fun pinningAModelDidNotMakeAnythingInstallable() {
        assertTrue(
            "DW_ASR_ARTIFACTS is still empty: the engine ships inside this APK now, and no server " +
                "serves an engine zip. A row here would need a real URL and a real digest.",
            DW_ASR_ARTIFACTS.isEmpty(),
        )
        val measurement = DwDeviceMeasurement(
            totalRamBytes = 5_927_968_768L,
            availableRamBytes = 1_533_587_456L,
            lowRamDevice = false,
            freeStorageBytes = 41_247_846_400L,
            abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            thermal = DwThermalState.NONE,
            charging = true,
            takenAtElapsedMs = 0L,
        )
        DwConnection.entries.forEach { connection ->
            val offer = dwAsrOffer(
                status = DwAsrRuntimeStatus(state = DwAsrRuntimeState.NOT_INSTALLED),
                measurement = measurement,
                connection = connection,
                runtimeInApk = false,
            )
            assertEquals(DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL, offer)
            assertFalse(dwAsrMayInstall(offer))
            assertFalse(
                "and on the shipped build nothing is installable either, for the other reason",
                dwAsrMayInstall(
                    dwAsrOffer(
                        status = DwAsrRuntimeStatus(state = DwAsrRuntimeState.NOT_INSTALLED),
                        measurement = measurement,
                        connection = connection,
                        runtimeInApk = true,
                    )
                )
            )
        }
    }

    private val labels = mapOf("hi-IN" to "Hindi", "or-IN" to "Odia", "bn-IN" to "Bengali")

    private val hindiOnly = DW_TIER1_CATALOGUE.first()

    @Test
    fun theSentenceAgreesWithItsSubjectWhenOneLanguageIsAlreadyCovered() {
        val hindiInstalled = DwRecognitionSupport(installedOnDevice = listOf("hi-IN", "en-IN", "en-GB"))
        assertEquals(
            "Adds nothing: Hindi already works offline.",
            dwAsrModelWhatItBuysSentence(labels, hindiInstalled, plans = listOf(hindiOnly)),
        )
    }

    @Test
    fun theSameSentenceStaysPluralWhenMoreThanOneLanguageIsCovered() {
        val bothInstalled = DwRecognitionSupport(installedOnDevice = listOf("hi-IN", "bn-IN"))
        val twoLanguages = hindiOnly.copy(languages = listOf("hi-IN", "bn-IN"))
        assertEquals(
            "Adds nothing: Hindi, Bengali already work offline.",
            dwAsrModelWhatItBuysSentence(labels, bothInstalled, plans = listOf(twoLanguages)),
        )
    }

    @Test
    fun aLanguageThePlatformDoesNotCoverIsReportedAsAnAdditionAndNotSubtractedAway() {
        val hindiOnlyPlatform = DwRecognitionSupport(installedOnDevice = listOf("hi-IN"))
        val hearsBoth = hindiOnly.copy(languages = listOf("hi-IN", "or-IN"))
        assertEquals(
            "Adds offline dictation in Odia.",
            dwAsrModelWhatItBuysSentence(labels, hindiOnlyPlatform, plans = listOf(hearsBoth)),
        )
    }

    @Test
    fun anAndroidVersionThatCannotBeAskedIsNotReadAsAPhoneWithNoPacks() {
        val sentence = dwAsrModelWhatItBuysSentence(labels, support = null, plans = listOf(hindiOnly))
        assertTrue(sentence.contains("Serves Hindi."))
        assertTrue(sentence.contains("cannot be asked"))
        assertFalse("it must not claim an addition it could not compute", sentence.contains("Adds"))
    }

    @Test
    fun aModelServingNoneOfOurLanguagesSaysSoRatherThanNamingNothing() {
        val servesNothingOfOurs = hindiOnly.copy(languages = listOf("sv-SE"))
        val sentence = dwAsrModelWhatItBuysSentence(
            labels,
            DwRecognitionSupport(installedOnDevice = listOf("hi-IN")),
            plans = listOf(servesNothingOfOurs),
        )
        assertEquals(
            "Adds no languages this app uses.",
            sentence,
        )
        assertEquals(
            "There is no speech model to install.",
            dwAsrModelWhatItBuysSentence(labels, null, plans = emptyList()),
        )
    }

    @Test
    fun theAgreementHelperIsExplicitAboutOneVersusManyAndZero() {
        assertEquals("works", dwPluralVerb(1, "works", "work"))
        assertEquals("work", dwPluralVerb(2, "works", "work"))
        assertEquals("work", dwPluralVerb(0, "works", "work"))
        assertEquals("pack", dwPluralNoun(1, "pack", "packs"))
        assertEquals("packs", dwPluralNoun(7, "pack", "packs"))
    }
}
