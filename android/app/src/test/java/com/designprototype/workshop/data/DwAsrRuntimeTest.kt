package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAsrRuntimeTest {

    private val mib: Long = 1024L * 1024L

    private fun digest(of: Char) = of.toString().repeat(64)

    private val NOT_A_REAL_ARTIFACT = DwAsrArtifact(
        abi = "arm64-v8a",
        url = "https://example.invalid/not-a-real-engine/arm64-v8a.zip",
        sha256 = digest('a'),
        downloadBytes = 20L * mib,
        libraries = listOf(
            DwAsrLibrary("libonnxruntime.so", digest('b'), 15L * mib),
            DwAsrLibrary("libsherpa-onnx-jni.so", digest('c'), 9L * mib),
        ),
        upstreamVersion = "no upstream — this artifact is a test fixture",
        provenance = "invented in DwAsrRuntimeTest; nothing was downloaded to produce it",
    )

    private val NOT_A_REAL_ARM32_ARTIFACT = NOT_A_REAL_ARTIFACT.copy(
        abi = "armeabi-v7a",
        url = "https://example.invalid/not-a-real-engine/armeabi-v7a.zip",
        sha256 = digest('d'),
        libraries = listOf(DwAsrLibrary("libsherpa-onnx-jni.so", digest('e'), 6L * mib)),
    )

    private val invented = listOf(NOT_A_REAL_ARTIFACT, NOT_A_REAL_ARM32_ARTIFACT)

    private val NOT_A_REAL_MODEL = DwModelPlan(
        modelId = "not-a-real-asr-model-invented-for-this-test",
        quantisation = "int8",
        abi = "arm64-v8a",
        languages = null,
        contextCapTokens = 1024,
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 300L * mib,
        peakRssBytes = 700L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    private val roomyPhone = DwDeviceMeasurement(
        totalRamBytes = 3_700L * mib,
        availableRamBytes = 1_100L * mib,
        lowRamDevice = false,
        freeStorageBytes = 12_000L * mib,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
    )

    private val installedStatus = DwAsrRuntimeStatus(
        state = DwAsrRuntimeState.INSTALLED,
        artifact = NOT_A_REAL_ARTIFACT,
        verifiedSha256 = NOT_A_REAL_ARTIFACT.libraries.map { it.sha256 },
    )

    private fun offer(
        status: DwAsrRuntimeStatus = DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
        measurement: DwDeviceMeasurement = roomyPhone,
        connection: DwConnection = DwConnection.UNMETERED,
        catalogue: List<DwAsrArtifact> = invented,
        models: List<DwModelPlan> = listOf(NOT_A_REAL_MODEL),
        runtimeInApk: Boolean = false,
    ) = dwAsrOffer(status, measurement, connection, catalogue, models, runtimeInApk)

    @Test
    fun `the shipped build says the engine is part of the app, on every handset and connection`() {
        listOf(roomyPhone, DwDeviceMeasurement()).forEach { device ->
            DwConnection.entries.forEach { connection ->
                DwAsrRuntimeState.entries.forEach { state ->
                    val status = when (state) {
                        DwAsrRuntimeState.INSTALLED -> return@forEach
                        else -> DwAsrRuntimeStatus(state)
                    }
                    val shipped = offer(
                        status = status,
                        measurement = device,
                        connection = connection,
                        runtimeInApk = true,
                    )
                    assertEquals(
                        "an engine inside the APK is not \"not installed\" and is not \"nothing " +
                            "published\" — on $device / $connection / $state",
                        DwAsrOffer.BUNDLED_IN_THIS_BUILD,
                        shipped,
                    )
                    assertFalse(
                        "there is nothing to install, so no control may be drawn",
                        dwAsrMayInstall(shipped)
                    )
                }
            }
        }
        val sentence = dwAsrOfferSentence(DwAsrOffer.BUNDLED_IN_THIS_BUILD, roomyPhone)
        assertTrue("it has to say the engine is here", sentence.contains("part of the app itself"))
        assertTrue(
            "and it must NOT let a designer read that as offline dictation being ready — the model " +
                "is the half that decides which languages work",
            sentence.contains("speech model on the card below")
        )
    }

    @Test
    fun `nothing is published, so nothing is offered on any handset or any connection`() {
        assertTrue(
            "docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md lists what must exist before a row belongs here",
            DW_ASR_ARTIFACTS.isEmpty()
        )
        listOf(roomyPhone, DwDeviceMeasurement()).forEach { device ->
            DwConnection.entries.forEach { connection ->
                val real = dwAsrOffer(
                    DwAsrRuntimeStatus(), device, connection, runtimeInApk = false
                )
                assertEquals(DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL, real)
                assertFalse(
                    "no control that spends a designer's data may be drawn today",
                    dwAsrMayInstall(real)
                )
                assertFalse(
                    "and none on the shipped build either, for the other reason",
                    dwAsrMayInstall(
                        dwAsrOffer(DwAsrRuntimeStatus(), device, connection, runtimeInApk = true)
                    )
                )
            }
        }
    }

    @Test
    fun `an engine with no model to feed it is refused, and the refusal says what is missing`() {
        assertEquals(DwAsrOffer.NO_MODEL_TO_FEED_IT, offer(models = emptyList()))
        assertFalse(dwAsrMayInstall(DwAsrOffer.NO_MODEL_TO_FEED_IT))
        val sentence = dwAsrOfferSentence(DwAsrOffer.NO_MODEL_TO_FEED_IT, roomyPhone)
        assertEquals("one state, one sentence, everywhere", DW_ASR_ENGINE_WITHOUT_A_VOICE, sentence)
        assertTrue("it has to say the engine arrives with no voice", sentence.contains("without a voice"))
        assertTrue(
            "and it has to say what is being waited for, or it is a dead end",
            sentence.contains("measured")
        )
    }

    @Test
    fun `the real Tier 1 model catalogue is what decides it, not a second opinion`() {
        assertEquals(
            DwAsrOffer.NO_MODEL_TO_FEED_IT,
            dwAsrOffer(
                DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                roomyPhone,
                DwConnection.UNMETERED,
                invented,
                modelCatalogue = emptyList(),
                runtimeInApk = false,
            )
        )
        assertTrue(
            "the shipped catalogue has a measured row, so this must NOT be the answer today",
            DW_TIER1_CATALOGUE.isNotEmpty()
        )
        assertNotEquals(
            "with a model in the catalogue the engine has something to say, and the offer moves on " +
                "to the questions about this handset",
            DwAsrOffer.NO_MODEL_TO_FEED_IT,
            dwAsrOffer(
                DwAsrRuntimeStatus(DwAsrRuntimeState.NOT_INSTALLED),
                roomyPhone,
                DwConnection.UNMETERED,
                invented,
                runtimeInApk = false,
            )
        )
    }

    @Test
    fun `nothing verifies against a blank digest`() {
        assertEquals(DwAsrVerification.NO_PINNED_DIGEST, dwAsrVerify("", ""))
        assertEquals(DwAsrVerification.NO_PINNED_DIGEST, dwAsrVerify("   ", digest('a')))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), ""))
    }

    @Test
    fun `only a full matching digest verifies, and case does not decide it`() {
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), digest('a')))
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), digest('A')))
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), "  ${digest('a')}\n"))
        assertEquals(DwAsrVerification.MISMATCH, dwAsrVerify(digest('a'), digest('b')))
    }

    @Test
    fun `a truncated or non-hex digest is malformed, never a match`() {
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), digest('a').take(63)))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a').take(8), digest('a').take(8)))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), "z".repeat(64)))
        assertFalse(dwAsrIsSha256(""))
        assertFalse(dwAsrIsSha256(digest('a') + "0"))
        assertTrue(dwAsrIsSha256(digest('a')))
    }

    @Test
    fun `an installed status cannot exist without every library verified in this run`() {
        assertTrue(
            runCatching { installedStatus.copy(artifact = null) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertTrue(
            runCatching { installedStatus.copy(verifiedSha256 = emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        val halfDone = runCatching {
            installedStatus.copy(verifiedSha256 = listOf(NOT_A_REAL_ARTIFACT.libraries.first().sha256))
        }.exceptionOrNull()
        assertTrue(halfDone is IllegalArgumentException)
        assertTrue(
            "the refusal must be a sentence naming the next move, not a code",
            (halfDone?.message ?: "").contains("hashed")
        )
        assertTrue(
            runCatching {
                installedStatus.copy(
                    verifiedSha256 = listOf(
                        NOT_A_REAL_ARTIFACT.libraries.first().sha256,
                        NOT_A_REAL_ARTIFACT.libraries.first().sha256,
                    )
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { installedStatus.copy(verifiedSha256 = listOf(digest('f'), digest('9'))) }
                .exceptionOrNull() is IllegalArgumentException
        )
        val reordered = installedStatus.copy(
            verifiedSha256 = NOT_A_REAL_ARTIFACT.libraries.map { it.sha256 }.reversed()
        )
        assertTrue(dwAsrMayLoad(reordered))
    }

    @Test
    fun `only a fully verified installed status may load`() {
        assertTrue(dwAsrMayLoad(installedStatus))
        DwAsrRuntimeState.entries.filter { it != DwAsrRuntimeState.INSTALLED }.forEach { state ->
            assertFalse(
                "$state must not be loadable",
                dwAsrMayLoad(
                    DwAsrRuntimeStatus(
                        state = state,
                        artifact = NOT_A_REAL_ARTIFACT,
                        verifiedSha256 = NOT_A_REAL_ARTIFACT.libraries.map { it.sha256 },
                    )
                )
            )
        }
        assertFalse(dwAsrMayLoad(DwAsrRuntimeStatus()))
        assertFalse(dwAsrAllVerified(null, listOf(digest('b'), digest('c'))))
    }

    @Test
    fun `an artifact cannot be served over plain http`() {
        val thrown = runCatching {
            NOT_A_REAL_ARTIFACT.copy(url = "http://example.invalid/engine.zip")
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertTrue((thrown?.message ?: "").contains("TLS"))
        assertTrue(
            runCatching { NOT_A_REAL_ARTIFACT.copy(url = "ftp://example.invalid/engine.zip") }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `an artifact cannot be pinned with a missing or malformed digest`() {
        listOf("", "   ", digest('a').take(10), "not-a-digest").forEach { bad ->
            assertTrue(
                "“$bad” must not be accepted as a pinned digest",
                runCatching { NOT_A_REAL_ARTIFACT.copy(sha256 = bad) }.exceptionOrNull()
                    is IllegalArgumentException
            )
        }
    }

    @Test
    fun `a library name that is a path is refused, so nothing can be written outside filesDir`() {
        listOf(
            "../databases/workshop.db",
            "subdir/libfoo.so",
            "..\\libfoo.so",
            "libfoo.so/..",
        ).forEach { bad ->
            assertTrue(
                "“$bad” must not be accepted as a library name",
                runCatching { DwAsrLibrary(bad, digest('b'), 1L) }.exceptionOrNull()
                    is IllegalArgumentException
            )
        }
        assertTrue(
            runCatching { DwAsrLibrary("engine.bin", digest('b'), 1L) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    @Test
    fun `an artifact's ABI is checked to be a bare name, because it is also a directory`() {
        listOf("../databases", "arm64-v8a/..", "..\\arm64-v8a", "lib/arm64", "  ").forEach { bad ->
            assertTrue(
                "“$bad” must not be accepted as an ABI",
                runCatching { NOT_A_REAL_ARTIFACT.copy(abi = bad) }.exceptionOrNull()
                    is IllegalArgumentException
            )
        }
        assertEquals("arm64-v8a", NOT_A_REAL_ARTIFACT.copy(abi = "arm64-v8a").abi)
    }

    @Test
    fun `an artifact cannot pin two libraries with one name, or none at all`() {
        assertTrue(
            runCatching { NOT_A_REAL_ARTIFACT.copy(libraries = emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        val duplicated = NOT_A_REAL_ARTIFACT.libraries.first()
        assertTrue(
            "two libraries with one name would be written to one path and one would vanish",
            runCatching { NOT_A_REAL_ARTIFACT.copy(libraries = listOf(duplicated, duplicated)) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `the installed size is derived from the libraries, so it cannot contradict them`() {
        assertEquals(24L * mib, NOT_A_REAL_ARTIFACT.installedBytes)
        assertEquals(
            24L * mib + 20L * mib + DW_ASR_FREE_STORAGE_MARGIN_BYTES,
            dwAsrStorageNeededBytes(NOT_A_REAL_ARTIFACT)
        )
    }

    @Test
    fun `the handset's own ABI order decides, not the order rows were written in`() {
        val armFirst = listOf(NOT_A_REAL_ARM32_ARTIFACT, NOT_A_REAL_ARTIFACT)
        assertEquals(
            "arm64-v8a",
            dwAsrArtifactFor(listOf("arm64-v8a", "armeabi-v7a"), armFirst)?.abi
        )
        assertEquals(
            "armeabi-v7a",
            dwAsrArtifactFor(listOf("armeabi-v7a"), armFirst)?.abi
        )
    }

    @Test
    fun `a processor with no build gets a different answer from a processor nobody could read`() {
        assertEquals(
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR,
            offer(measurement = roomyPhone.copy(abis = listOf("x86_64")))
        )
        assertEquals(
            DwAsrOffer.PROCESSOR_UNMEASURED,
            offer(measurement = roomyPhone.copy(abis = emptyList()))
        )
        val sentence = dwAsrOfferSentence(
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR,
            roomyPhone.copy(abis = listOf("x86_64")),
            invented,
        )
        assertTrue(sentence.contains("x86_64"))
        assertTrue(sentence.contains("arm64-v8a"))
    }

    @Test
    fun `an unmeasured storage reading refuses rather than passing`() {
        assertEquals(
            DwAsrOffer.STORAGE_UNMEASURED,
            offer(measurement = roomyPhone.copy(freeStorageBytes = null))
        )
    }

    @Test
    fun `an engine that would fill the phone is refused, and the sentence prints what is free`() {
        val tight = roomyPhone.copy(freeStorageBytes = 100L * mib)
        assertEquals(DwAsrOffer.NOT_ENOUGH_STORAGE, offer(measurement = tight))
        val sentence = dwAsrOfferSentence(DwAsrOffer.NOT_ENOUGH_STORAGE, tight)
        assertTrue("the designer needs the number, not just the verdict", sentence.contains("105 MB"))
    }

    @Test
    fun `the model refusal comes before storage but after the processor, following dwTier2Offer`() {
        val noBuild = roomyPhone.copy(abis = listOf("x86_64"), freeStorageBytes = 1L * mib)
        assertEquals(
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR,
            offer(measurement = noBuild, models = emptyList())
        )
        val tight = roomyPhone.copy(freeStorageBytes = 1L * mib)
        assertEquals(
            DwAsrOffer.NO_MODEL_TO_FEED_IT,
            offer(measurement = tight, models = emptyList())
        )
    }

    @Test
    fun `nothing published outranks a disk nobody could read`() {
        assertEquals(
            DwAsrOffer.NOTHING_PUBLISHED_TO_INSTALL,
            dwAsrOffer(
                DwAsrRuntimeStatus(),
                roomyPhone,
                DwConnection.UNMETERED,
                emptyList(),
                runtimeInApk = false,
            )
        )
        assertEquals(DwAsrOffer.UNKNOWN, offer(status = DwAsrRuntimeStatus()))
        assertFalse(dwAsrMayInstall(DwAsrOffer.UNKNOWN))
    }

    @Test
    fun `no connection never draws a control, however installable the engine is`() {
        assertEquals(DwAsrOffer.NO_CONNECTION, offer(connection = DwConnection.NONE))
        assertFalse(dwAsrMayInstall(DwAsrOffer.NO_CONNECTION))
        assertEquals(DwAsrOffer.INSTALL, offer(connection = DwConnection.METERED))
        assertEquals(DwAsrOffer.INSTALL, offer(connection = DwConnection.UNMETERED))
    }

    @Test
    fun `a fetch in flight is never offered again, so one file is not paid for twice`() {
        assertEquals(
            DwAsrOffer.IN_PROGRESS,
            offer(status = DwAsrRuntimeStatus(DwAsrRuntimeState.DOWNLOADING, NOT_A_REAL_ARTIFACT))
        )
        assertFalse(dwAsrMayInstall(DwAsrOffer.IN_PROGRESS))
    }

    @Test
    fun `an installed engine is never offered, on any connection`() {
        DwConnection.entries.forEach { connection ->
            assertEquals(
                DwAsrOffer.ALREADY_INSTALLED,
                offer(status = installedStatus, connection = connection)
            )
        }
        assertFalse(dwAsrMayInstall(DwAsrOffer.ALREADY_INSTALLED))
    }

    @Test
    fun `a failed attempt may be retried, because it fetched nothing to pay twice for`() {
        val failed = DwAsrRuntimeStatus(
            DwAsrRuntimeState.FAILED,
            NOT_A_REAL_ARTIFACT,
            failure = DW_ASR_MISMATCH_SENTENCE,
        )
        assertEquals(DwAsrOffer.RETRY, offer(status = failed))
        assertTrue(dwAsrMayInstall(DwAsrOffer.RETRY))
        assertEquals(DwAsrOffer.NO_CONNECTION, offer(status = failed, connection = DwConnection.NONE))
    }

    @Test
    fun `exactly two offers may ever draw a control, and both need a connection`() {
        DwAsrOffer.entries.forEach { candidate ->
            if (dwAsrMayInstall(candidate)) {
                assertTrue(
                    "only INSTALL and RETRY may spend a designer's data: $candidate",
                    candidate == DwAsrOffer.INSTALL || candidate == DwAsrOffer.RETRY
                )
            }
        }
        DwConnection.entries.forEach { connection ->
            val drawn = dwAsrMayInstall(offer(connection = connection))
            assertEquals(connection != DwConnection.NONE, drawn)
        }
    }

    @Test
    fun `the engine sizes are the shipped engine's own library bytes, per ABI`() {
        assertEquals(24_169_352L, DW_ASR_ENGINE_BYTES_ARM64)
        assertEquals(16_437_284L, DW_ASR_ENGINE_BYTES_ARM32)
        assertEquals("24 MB", dwBytesLabel(DW_ASR_ENGINE_BYTES_ARM64))
        assertEquals("16 MB", dwBytesLabel(DW_ASR_ENGINE_BYTES_ARM32))
    }

    @Test
    fun `the size is named before the tap, per processor, unlike a Google pack`() {
        val arm64 = dwAsrCostSentence(roomyPhone, DwConnection.METERED)
        assertTrue(arm64.contains("24 MB"))
        assertTrue("the processor is named, because the number depends on it", arm64.contains("arm64-v8a"))
        assertTrue("the meter warning comes before the tap", arm64.contains("mobile data"))

        val arm32 = dwAsrCostSentence(roomyPhone.copy(abis = listOf("armeabi-v7a")), DwConnection.UNMETERED)
        assertTrue(arm32.contains("16 MB"))
        assertFalse("a v7a handset must not be shown the arm64 figure", arm32.contains("24 MB"))

        val pinned = dwAsrCostSentence(roomyPhone, DwConnection.UNMETERED, NOT_A_REAL_ARTIFACT)
        assertTrue(pinned.contains(dwBytesLabel(NOT_A_REAL_ARTIFACT.downloadBytes)))
    }

    @Test
    fun `an unmeasured processor is told the word unmeasured, never given the ARM figure`() {
        assertEquals(null, dwAsrMeasuredEngineBytes(listOf("x86_64", "x86")))
        assertEquals(null, dwAsrMeasuredEngineBytes(emptyList()))
        val sentence = dwAsrCostSentence(roomyPhone.copy(abis = listOf("x86_64")), DwConnection.UNMETERED)
        assertTrue(sentence.contains("unmeasured"))
        assertFalse(Regex("""\d+\s*MB""").containsMatchIn(sentence))
        val silent = dwAsrCostSentence(roomyPhone.copy(abis = emptyList()), DwConnection.NONE)
        assertTrue(silent.contains("unmeasured"))
    }

    @Test
    fun `the processor named beside the size is the one the size was measured for`() {
        val translated = roomyPhone.copy(abis = listOf("x86_64", "x86", "armeabi-v7a"))
        assertEquals("armeabi-v7a", dwAsrMeasuredAbi(translated.abis))
        assertEquals(DW_ASR_ENGINE_BYTES_ARM32, dwAsrMeasuredEngineBytes(translated.abis))
        val sentence = dwAsrCostSentence(translated, DwConnection.METERED)
        assertTrue("the figure is the 32-bit one, because that is what would run", sentence.contains("16 MB"))
        assertTrue("and the processor named is the one it was measured on", sentence.contains("armeabi-v7a"))
        assertFalse(
            "an ARM figure must never be printed beside an x86 processor name",
            sentence.contains("x86")
        )
        val pinned = dwAsrCostSentence(translated, DwConnection.UNMETERED, NOT_A_REAL_ARM32_ARTIFACT)
        assertTrue(pinned.contains("armeabi-v7a"))
        assertEquals(null, dwAsrMeasuredAbi(listOf("x86_64", "x86")))
        assertTrue(
            dwAsrCostSentence(roomyPhone.copy(abis = listOf("x86_64", "x86")), DwConnection.NONE)
                .contains("(x86_64) is unmeasured")
        )
    }

    @Test
    fun `every offer has its own sentence, and every one of them is a sentence`() {
        val sentences = DwAsrOffer.entries.map { dwAsrOfferSentence(it, roomyPhone, invented) }
        assertEquals(
            "two states sharing a sentence read as one state, and these do not mean the same",
            DwAsrOffer.entries.size,
            sentences.toSet().size
        )
        sentences.forEach { sentence ->
            assertTrue("a refusal must be a sentence: $sentence", sentence.trimEnd().endsWith("."))
            assertTrue(
                "says what to do or what holds: $sentence",
                listOf("tap", "try again", "add ", "install", "stay on", "works", "free some space", "built for")
                    .any { sentence.lowercase().contains(it) }
            )
        }
        val labels = DwAsrRuntimeState.entries.map { dwAsrStateLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `no sentence promises the engine works today, and the pitch does not promise Odia`() {
        assertTrue("the pitch names the language the packs miss", DW_ASR_OFFER_BLURB.contains("Odia"))
        assertTrue(
            "and says it is a download the designer chooses",
            DW_ASR_OFFER_BLURB.contains("download you choose")
        )
        listOf("accurate", "accurately", "perfectly", "as well as").forEach { claim ->
            assertFalse(
                "the pitch must not claim a quality nobody has measured: “$claim”",
                DW_ASR_OFFER_BLURB.contains(claim, ignoreCase = true)
            )
        }
        assertTrue(DW_ASR_WHAT_IT_BUYS.startsWith("With the engine and a language model"))
    }

    @Test
    fun `the pitch does not leave a designer believing an engine is a download away`() {
        assertTrue(
            "the collapsed card has to say an engine is not sufficient on its own",
            DW_ASR_OFFER_BLURB.contains("needs a speech model")
        )
        assertTrue(
            "and it has to send the designer to the answer for THIS phone rather than assert one",
            DW_ASR_OFFER_BLURB.contains("can be installed on this phone today")
        )
        listOf("is a download away", "just install", "simply install").forEach { claim ->
            assertFalse(
                "the pitch must not read as an available download: “$claim”",
                DW_ASR_OFFER_BLURB.contains(claim, ignoreCase = true)
            )
        }
        assertTrue(DW_ASR_ENGINE_WITHOUT_A_VOICE.contains("nothing can be installed yet"))
        assertTrue(DW_ASR_CARD_BLURB != DW_ASR_OFFER_BLURB)
        assertTrue(
            "the settings blurb promises nothing either",
            DW_ASR_CARD_BLURB.contains("It is optional")
        )
    }

    @Test
    fun `the verification sentence claims integrity and not provenance`() {
        assertTrue(DW_ASR_VERIFY_SENTENCE.contains("fingerprint built into this app"))
        assertTrue(DW_ASR_VERIFY_SENTENCE.contains("thrown away if it does not match"))
        listOf("signed", "signature", "audited", "certified", "official", "safe").forEach { claim ->
            assertFalse(
                "the verification sentence must not claim “$claim”",
                DW_ASR_VERIFY_SENTENCE.contains(claim, ignoreCase = true)
            )
        }
        assertTrue(DW_ASR_MISMATCH_SENTENCE.contains("deleted"))
        assertTrue(DW_ASR_MISMATCH_SENTENCE.contains("twice"))
    }
}
