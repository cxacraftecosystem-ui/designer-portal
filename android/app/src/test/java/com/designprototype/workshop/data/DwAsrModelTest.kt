package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PINNED SPEECH MODEL, AND THE GUARDS THAT STOP IT BECOMING A CLAIM.
 *
 * Sibling of `DwAsrRuntimeTest`, and written for the mirror-image reason. That file's job is to pin
 * that **nothing** is installable while `DW_ASR_ARTIFACTS` is empty. This one's job begins the moment
 * a real row exists: [DW_ASR_MODELS] now holds one, with digests taken off bytes that were downloaded
 * and hashed, so the risk moves from "an empty list going live quietly" to "a row drifting away from
 * the file it describes".
 *
 * **THE THINGS BELOW ARE NOT RE-DERIVED FROM THE CATALOGUE THEY CHECK.** A test that reads a constant
 * and asserts the constant equals itself is a test that cannot fail, which
 * `docs/DEVICE-TIER-MEASUREMENT.md` spent a section on after shipping two of them. The digests and
 * sizes are therefore written out again here, by hand, from the same `sha256sum` output the
 * catalogue was filled from — so an edit to one and not the other is what goes red.
 */
class DwAsrModelTest {

    /**
     * The digests, spelled out independently of the catalogue.
     *
     * If this test fails, do NOT copy the new value across from the source file to make it pass.
     * Re-download the published artifact, hash it, and change both — a digest that moved without the
     * bytes moving is either a mistake or the thing this whole feature exists to catch.
     */
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

        // Derived, not declared — the reason `onDiskBytes` is a getter rather than a field.
        assertEquals(365_438_543L, model.onDiskBytes)
    }

    /**
     * **A MODEL IS NOT A CAPABILITY, IT IS A CAPABILITY IN SOME LANGUAGES.**
     *
     * The language note is the one field a reader consults to find out how much of the language
     * story is evidence, so it must keep the claimed and the measured apart in words. This pins the
     * separation rather than the prose: the note has to name who is claiming the big number, and it
     * has to contain the honest hedge about what a vocabulary proves.
     */
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
        // The words a card must never be able to reach for. `DW_ASR_VERIFY_SENTENCE` is guarded the
        // same way in `DwAsrRuntimeTest`, and for the same reason: a sentence that quietly gains an
        // assurance it never had is the failure mode nobody notices.
        listOf("guaranteed", "supports all", "any language", "certified").forEach { word ->
            assertFalse(
                "“$word” claims more than a handset reading can support.",
                note.lowercase().contains(word),
            )
        }
    }

    /**
     * The ABI walk is over the HANDSET'S list, primary-first, exactly as [dwAsrArtifactFor]'s is.
     *
     * The defect this pins is the one that function's KDoc describes: `ours.firstOrNull { it in
     * theirs }` compiles, reads almost the same, and hands a 64-bit phone the 32-bit engine whenever
     * the 32-bit row happens to be written first.
     */
    @Test
    fun theEngineAbiIsChosenByTheHandsetsOwnPreferenceOrder() {
        assertEquals(
            "arm64-v8a",
            dwAsrEngineAbiFor(listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
        assertEquals("armeabi-v7a", dwAsrEngineAbiFor(listOf("armeabi-v7a", "armeabi")))
        // An ARC / Houdini handset runs this app's 32-bit code and is owed the 32-bit engine, even
        // though its FIRST ABI is one no engine here was built for.
        assertEquals("armeabi-v7a", dwAsrEngineAbiFor(listOf("x86_64", "x86", "armeabi-v7a")))
        // No engine for it at all. Distinct from the empty case below, which is a different fact.
        assertNull(dwAsrEngineAbiFor(listOf("x86_64", "x86")))
        // Empty is "the read failed", not "matches nothing" — the caller turns it into
        // PROCESSOR_UNMEASURED and must not be handed something it could mistake for an answer.
        assertNull(dwAsrEngineAbiFor(emptyList()))
    }

    /**
     * Every constructor guard fires, because each is the only thing standing between a careless
     * catalogue edit and a phone loading bytes nobody checked.
     */
    @Test
    fun aModelFileCannotBePinnedWithoutARealDigestARealSizeAndABareName() {
        val digest = "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"

        // A blank digest is the one that matters most: `dwAsrVerify` answers NO_PINNED_DIGEST for
        // it, so a row that got this far would fail closed — but it must not get this far.
        listOf("", "abc", digest.dropLast(1), digest + "0").forEach { bad ->
            runCatching { DwAsrModelFile("model.int8.onnx", bad, 1L) }
                .onSuccess { throw AssertionError("“$bad” was accepted as a SHA-256 and is not one.") }
        }
        runCatching { DwAsrModelFile("../databases/workshop.db", digest, 1L) }
            .onSuccess { throw AssertionError("A path was accepted where a bare file name is required.") }
        runCatching { DwAsrModelFile("model.int8.onnx", digest, 0L) }
            .onSuccess { throw AssertionError("A zero size was accepted; the storage gate reads it.") }

        // And a model may not hold the same file name twice — one would overwrite the other on disk
        // and the digest check would then blame the file that lost.
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

    /**
     * **A HEAD MAY NOT NAME A FILE NOBODY PINNED, AND THAT IS THE HOLE THIS CLOSES.**
     *
     * `DwAsrModelStatus` may only say INSTALLED when every file in `files` hashed to its pinned digest
     * in this run, and [DwAsrSpeechModel] then opens the paths a HEAD names. Those are two lists, and
     * if they can disagree the app can report "every byte checked" and hand the graph executor a path
     * nothing ever hashed — or, more likely, a path that is simply not there, after a designer has
     * spent 365 MB. The `require` makes that unrepresentable rather than caught later.
     */
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

        // The graph is pinned and the vocabulary is not: the case that decodes to confident nonsense
        // rather than to an error, which is why it is refused at construction.
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

    /**
     * **RESOLVING A LANGUAGE TO A GRAPH: THE PER-LANGUAGE HEAD WINS, AND A MISS IS A MISS.**
     *
     * The second half is the one worth pinning. Falling back to *any* head when the requested language
     * has none would decode Odia through, say, the Malayalam head — which is not an error, it is a
     * fluent transcript in the wrong script. Measured on a real artifact:
     * `jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam` returns `ഹത്തിയോ ജിരഹ്` for the Odia
     * clip whose reference begins `ହାତୀ ଓ ଜିରାଫ`. `headFor` returning null is what keeps that off a form.
     */
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
        // A bare catalogue tag serves every region of it — `dwTagCovers`'s one deliberate widening.
        assertEquals("model-or.onnx", perLanguage.headFor("or-IN")?.graphFileName)
        assertEquals("model-ml.onnx", perLanguage.headFor("ml-IN")?.graphFileName)
        assertNull(
            "Hindi has no head in this artifact and must resolve to nothing. Substituting the Odia " +
                "head would return a fluent transcript in the wrong script, which a stage would store.",
            perLanguage.headFor("hi-IN"),
        )

        // A single-head model answers for every tag through the catch-all, which is the whole reason
        // `languageTag = null` exists rather than 1,600 rows.
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

    /**
     * **PINNING A MODEL MUST NOT HAVE TURNED THE ENGINE INSTALL LOOSE.**
     *
     * This is the assertion that would have caught the mistake this lane could most easily have
     * made. `dwAsrOffer` reads `DW_TIER1_CATALOGUE` to decide whether the engine would have anything
     * to say; [DW_ASR_MODELS] is a **different list**, added by this lane, and the two must not be
     * confused. Nothing is published to download and no `DwModelPlan` has been written, so every
     * handset still gets a refusal and no control that spends data may be drawn.
     */
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
            // `runtimeInApk = false` describes the build this assertion is about: one that does NOT
            // carry the engine, where "nothing published" is the honest answer. The shipped build
            // carries it and answers BUNDLED_IN_THIS_BUILD before this question is reached — pinned
            // in `DwAsrRuntimeTest`. Both are true of `DW_ASR_ARTIFACTS` being empty, which is what
            // this test is really about and is asserted above.
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

    // ---------------------------------------------------------------------------------------
    // WHAT THE MODEL BUYS THIS PHONE — the sentence on the Speech & AI card, and it was ungrammatical
    // on every handset in the fleet because it had NO TESTS AT ALL until 2026-08-13.
    // ---------------------------------------------------------------------------------------

    /** Hindi and Odia, the two the pinned plan's coverage is argued over. */
    private val labels = mapOf("hi-IN" to "Hindi", "or-IN" to "Odia", "bn-IN" to "Bengali")

    /** A plan that hears exactly Hindi — which is what `DW_TIER1_CATALOGUE`'s single row actually is. */
    private val hindiOnly = DW_TIER1_CATALOGUE.first()

    @Test
    fun theSentenceAgreesWithItsSubjectWhenOneLanguageIsAlreadyCovered() {
        /*
         * THE DEFECT, EXACTLY AS IT RENDERED. This arm read "…already work offline through Android's
         * own pack" with the verb and the noun both hard-coded plural, and ONE language is the only
         * case that can reach it: the pinned plan serves `hi-IN` alone. So the Speech & AI card of
         * every handset in the fleet showed *"Hindi already work offline through Android's own pack."*
         *
         * The fixture is the reading taken off the attached SM-M325F on 2026-08-13 — `hi-IN` installed.
         */
        val hindiInstalled = DwRecognitionSupport(installedOnDevice = listOf("hi-IN", "en-IN", "en-GB"))
        assertEquals(
            "Adds nothing on this phone: Hindi already works offline through Android's own pack.",
            dwAsrModelWhatItBuysSentence(labels, hindiInstalled, plans = listOf(hindiOnly)),
        )
    }

    @Test
    fun theSameSentenceStaysPluralWhenMoreThanOneLanguageIsCovered() {
        // The other half of the agreement, so a "fix" that merely swapped the hard-coded plural for a
        // hard-coded singular fails here. Both the verb and the NOUN inflect: one pack, two packs.
        val bothInstalled = DwRecognitionSupport(installedOnDevice = listOf("hi-IN", "bn-IN"))
        val twoLanguages = hindiOnly.copy(languages = listOf("hi-IN", "bn-IN"))
        assertEquals(
            "Adds nothing on this phone: Hindi, Bengali already work offline through Android's own packs.",
            dwAsrModelWhatItBuysSentence(labels, bothInstalled, plans = listOf(twoLanguages)),
        )
    }

    @Test
    fun aLanguageThePlatformDoesNotCoverIsReportedAsAnAdditionAndNotSubtractedAway() {
        /*
         * THE SUBTRACTION'S WHOLE POINT, IN THE DIRECTION THAT MAKES THE MODEL WORTH 365 MB. Given a
         * plan that hears Odia on a phone whose packs do not, the sentence must say what is NEW here
         * rather than what the model serves — the overclaim this signature was introduced to fix was
         * printing "dictation works with no signal at all in Hindi" on a phone that already had Hindi.
         */
        val hindiOnlyPlatform = DwRecognitionSupport(installedOnDevice = listOf("hi-IN"))
        val hearsBoth = hindiOnly.copy(languages = listOf("hi-IN", "or-IN"))
        assertEquals(
            "Adds offline dictation in Odia.",
            dwAsrModelWhatItBuysSentence(labels, hindiOnlyPlatform, plans = listOf(hearsBoth)),
        )
    }

    @Test
    fun anAndroidVersionThatCannotBeAskedIsNotReadAsAPhoneWithNoPacks() {
        // NULL SUPPORT IS NOT "THE PLATFORM HAS NOTHING". Reading it that way would restore the
        // overclaim in its other direction, promising an addition that may already be present. It says
        // what the model serves and states that the subtraction could not be made.
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
            "Serves none of this app's dictation languages, so it would add nothing here.",
            sentence,
        )
        // AND AN EMPTY CATALOGUE IS A DIFFERENT FACT FROM A MODEL THAT COVERS NOTHING. Collapsing them
        // would print "serves none of this app's languages" about a build that pins no model at all.
        assertEquals(
            "No speech model is measured in this build.",
            dwAsrModelWhatItBuysSentence(labels, null, plans = emptyList()),
        )
    }

    @Test
    fun theAgreementHelperIsExplicitAboutOneVersusManyAndZero() {
        // Named rather than inlined precisely so this can be asked. Zero takes the plural, which is
        // English; no caller here reaches it, because an empty list has its own sentence.
        assertEquals("works", dwPluralVerb(1, "works", "work"))
        assertEquals("work", dwPluralVerb(2, "works", "work"))
        assertEquals("work", dwPluralVerb(0, "works", "work"))
        assertEquals("pack", dwPluralNoun(1, "pack", "packs"))
        assertEquals("packs", dwPluralNoun(7, "pack", "packs"))
    }
}
