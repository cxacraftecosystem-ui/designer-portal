package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opt-in speech-engine install, on the desktop JVM.
 *
 * ── WHAT THIS FILE IS FOR ─────────────────────────────────────────────────────────────────────
 *
 * Two things, and they are of very different weights.
 *
 * The first is the ordinary one this repository already does well: the predicate standing between a
 * designer and 24 MB of their prepaid data allowance is as testable as `dwPackOffer`, and every state
 * has its own sentence so that one fact cannot read two ways on two cards.
 *
 * **The second is that this download is EXECUTABLE CODE.** A language pack is data fetched by the
 * platform; a `.so` loaded into this process runs alongside artisans' Aadhaar numbers and up to two
 * weeks of unsynced fieldwork. The verification tests below are therefore not "coverage" — each one
 * pins a way in which a plausible-looking edit would let unverified native code be loaded, and the
 * most important of them is [`nothing verifies against a blank digest`], because `expected == actual`
 * on two empty strings is `true` and that one line would turn the whole feature into decoration.
 *
 * ── THE FIXTURE RULE THIS FILE OBEYS ──────────────────────────────────────────────────────────
 *
 * `DwLanguagePackTest` carries the scar: a fixture named after a real handset once asserted
 * capabilities that handset did not have, and the suite agreed with a device that does not exist for
 * weeks. So [NOT_A_REAL_ARTIFACT] says at every use site that it is invented, its digests are
 * obviously synthetic (`aa…`, `bb…`), and its URL is not a URL anybody serves. **Nothing in this file
 * is a measurement**, except the two engine sizes, which are quoted from
 * docs/ASR-RUNTIME-MEASUREMENT.md and are checked here for exactly that reason.
 */
class DwAsrRuntimeTest {

    private val mib: Long = 1024L * 1024L

    // ---------------------------------------------------------------------------------------
    // Fixtures — invented, and named so they cannot be mistaken for anything published
    // ---------------------------------------------------------------------------------------

    private fun digest(of: Char) = of.toString().repeat(64)

    /**
     * AN ARTIFACT THAT DOES NOT EXIST. Nothing is published, so nothing here was measured.
     *
     * Its two libraries are in the load order the real engine needs — `libonnxruntime.so` before
     * `libsherpa-onnx-jni.so`, because the second cannot resolve against the first otherwise — which
     * is the one structural thing about the real artifact this fixture imitates on purpose.
     */
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

    /** A model plan that does not exist either, for the one test that needs a non-empty catalogue. */
    private val NOT_A_REAL_MODEL = DwModelPlan(
        modelId = "not-a-real-asr-model-invented-for-this-test",
        quantisation = "int8",
        abi = "arm64-v8a",
        // Null, which is this app's word for "nobody has checked which languages it hears" — the
        // honest state for a fixture nobody has run, and it keeps this file's tests about the ENGINE
        // rather than about coverage, which `DwModelLanguagesTest` owns.
        languages = null,
        contextCapTokens = 1024,
        // A DECODER-SHAPED FIXTURE, so the cap above is the envelope and this sentence
        // restates it. Nothing here was measured; see this file's fixture rule.
        runBound = "one generation at the invented 1,024-token context cap above",
        onDiskBytes = 300L * mib,
        peakRssBytes = 700L * mib,
        measuredOn = "no handset — this plan is a test fixture",
        survivesBackgrounding = true,
    )

    /** A 64-bit handset in the fleet's class, with room. Shaped, not read off a phone. */
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
        /*
         * **EVERY TEST IN THIS FILE IS ABOUT A BUILD THAT DOES NOT CARRY THE ENGINE, AND SAYS SO
         * HERE ONCE RATHER THAN IN EACH OF THEM.**
         *
         * The engine went into the APK on 2026-08-12 evening, so `dwAsrOffer` now answers
         * `BUNDLED_IN_THIS_BUILD` before it looks at anything else — which makes the install offer,
         * and this whole file, unreachable on the shipped build. That is the correct shipped answer
         * (see [DwAsrOffer.BUNDLED_IN_THIS_BUILD], and the two cards that contradicted each other
         * while it was missing) and it is not a reason to delete the tests: the branch below it is
         * live code for any build without the vendored AAR, and it is the code that decides whether a
         * designer is charged for a download.
         */
        runtimeInApk: Boolean = false,
    ) = dwAsrOffer(status, measurement, connection, catalogue, models, runtimeInApk)

    // ---------------------------------------------------------------------------------------
    // Today's shipped answer. If any of these change, the shipped promise has changed.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the shipped build says the engine is part of the app, on every handset and connection`() {
        /*
         * **THE ONE ANSWER A DESIGNER ACTUALLY SEES, AND THE DEFECT IT CLOSES.**
         *
         * Read off the running app on the fleet's SM-M325F before this value existed: the engine card
         * said **"Speech engine — Not installed"** and *"there is nothing published for this app to
         * install yet"*, two cards above **"Tier 1 would run sherpa-onnx-omnilingual-asr-… on this
         * phone"** — both from one reading of one handset. This test is what stops that coming back.
         *
         * It also pins that no control is drawn: there is nothing to install, so an install button
         * would be the control that cannot work which this repository's own rule calls worse than an
         * absent one.
         */
        listOf(roomyPhone, DwDeviceMeasurement()).forEach { device ->
            DwConnection.entries.forEach { connection ->
                DwAsrRuntimeState.entries.forEach { state ->
                    val status = when (state) {
                        // INSTALLED cannot be constructed without a verified artifact, and the point
                        // of this test is the states a real handset can be in on a bundled build.
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
        // Not a tautology: this is what turns "no artifact has been published and no digest pinned"
        // from a sentence in a document into something a build fails on. A row added to
        // DW_ASR_ARTIFACTS without a published file and a measured digest breaks this test, which is
        // a conversation rather than a silent 24 MB fetch on the fleet.
        assertTrue(
            "docs/ASR-RUNTIME-DOWNLOAD-CONTRACT.md lists what must exist before a row belongs here",
            DW_ASR_ARTIFACTS.isEmpty()
        )
        listOf(roomyPhone, DwDeviceMeasurement()).forEach { device ->
            DwConnection.entries.forEach { connection ->
                /*
                 * `runtimeInApk = false`, BECAUSE THE CLAIM BEING PINNED IS ABOUT `DW_ASR_ARTIFACTS`
                 * AND NOT ABOUT THE SHIPPED CARD. On the shipped build the engine is inside the APK
                 * and the answer is BUNDLED_IN_THIS_BUILD before this question is reached — asserted
                 * two tests up. What this test protects is the sentence in the assertion above it: a
                 * row added to DW_ASR_ARTIFACTS without a published file and a measured digest still
                 * has to fail a test rather than turn a 24 MB fetch loose on the fleet, and it has to
                 * go on failing while the engine happens to be bundled, because the bundling could be
                 * undone by a build variant tomorrow.
                 */
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
        /*
         * THE CALL THIS LANE WAS ASKED TO MAKE AND ARGUE. Even with an artifact published and a phone
         * with room, the install is refused while no ASR model has been measured — because the engine
         * carries no model (docs/ASR-RUNTIME-MEASUREMENT.md §3: the AAR has no `assets/` entry at all)
         * and a designer who spends 24 MB to find dictation unchanged has been misled by this app.
         *
         * It is DISABLED WITH A SENTENCE rather than absent: the card is still on the screen, still
         * says what the engine is for and what it would cost, and states plainly that it would arrive
         * without a voice. What is absent is the BUTTON, which is the part that could not work.
         */
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
        /*
         * ONE LIST OF MEASURED ASR MODELS EXISTS IN THIS APP, and this test is about that and not
         * about how many rows are in it. `dwAsrOffer` reads `DW_TIER1_CATALOGUE` itself rather than
         * keeping a boolean of its own, so the install offer and the tier recommender cannot come to
         * disagree about whether a model exists.
         *
         * **IT USED TO ASSERT `DW_TIER1_CATALOGUE.isEmpty()` AND THEN THE CONSEQUENCE OF THAT.** The
         * catalogue now has a measured row, so the emptiness assertion has gone and the coupling —
         * which is the thing worth pinning — is asserted directly instead: pass an empty catalogue and
         * the offer is NO_MODEL_TO_FEED_IT, pass one with a row and it is not, with nothing else
         * changing between the two calls.
         */
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

    // ---------------------------------------------------------------------------------------
    // Verification — every test here is a way unverified native code could have been loaded
    // ---------------------------------------------------------------------------------------

    @Test
    fun `nothing verifies against a blank digest`() {
        /*
         * THE MOST IMPORTANT TEST IN THIS FILE. `expected == actual` is `true` for two empty strings,
         * so a verifier written the obvious way passes when a build pins nothing — and a build that
         * pins nothing is exactly the state this app is in today. Fail closed, in both directions.
         */
        assertEquals(DwAsrVerification.NO_PINNED_DIGEST, dwAsrVerify("", ""))
        assertEquals(DwAsrVerification.NO_PINNED_DIGEST, dwAsrVerify("   ", digest('a')))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), ""))
    }

    @Test
    fun `only a full matching digest verifies, and case does not decide it`() {
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), digest('a')))
        // sha256sum, certutil and MessageDigest are not consistent about case, and a designer's engine
        // must not be rejected over the case of a hex string. A newline off a build log likewise.
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), digest('A')))
        assertEquals(DwAsrVerification.VERIFIED, dwAsrVerify(digest('a'), "  ${digest('a')}\n"))
        assertEquals(DwAsrVerification.MISMATCH, dwAsrVerify(digest('a'), digest('b')))
    }

    @Test
    fun `a truncated or non-hex digest is malformed, never a match`() {
        // A truncated digest is the shape a "close enough" comparison would accept — a prefix match on
        // 8 hex characters is 4 bytes of collision resistance, which is none.
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), digest('a').take(63)))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a').take(8), digest('a').take(8)))
        assertEquals(DwAsrVerification.MALFORMED, dwAsrVerify(digest('a'), "z".repeat(64)))
        assertFalse(dwAsrIsSha256(""))
        assertFalse(dwAsrIsSha256(digest('a') + "0"))
        assertTrue(dwAsrIsSha256(digest('a')))
    }

    @Test
    fun `an installed status cannot exist without every library verified in this run`() {
        /*
         * THE INVARIANT THAT MAKES "nothing is loaded that was not verified" MECHANICAL RATHER THAN
         * REMEMBERED. Each `copy` below is a plausible-looking edit — a future fast-start check that
         * finds the files present, a partial re-verify that stopped at the first library — and each
         * one has to be impossible to express, not merely discouraged.
         */
        // No artifact at all: verified against what?
        assertTrue(
            runCatching { installedStatus.copy(artifact = null) }.exceptionOrNull()
                is IllegalArgumentException
        )
        // Nothing hashed: the "the files are there, skip the hash" shortcut.
        assertTrue(
            runCatching { installedStatus.copy(verifiedSha256 = emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        // ONE OF TWO HASHED. Half a verification is not most of a yes: the library nobody looked at
        // is the one that would be loaded.
        val halfDone = runCatching {
            installedStatus.copy(verifiedSha256 = listOf(NOT_A_REAL_ARTIFACT.libraries.first().sha256))
        }.exceptionOrNull()
        assertTrue(halfDone is IllegalArgumentException)
        assertTrue(
            "the refusal must be a sentence naming the next move, not a code",
            (halfDone?.message ?: "").contains("hashed")
        )
        // One hash counted twice, which is what a `containsAll` would have let through.
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
        // A digest that matches nothing pinned.
        assertTrue(
            runCatching { installedStatus.copy(verifiedSha256 = listOf(digest('f'), digest('9'))) }
                .exceptionOrNull() is IllegalArgumentException
        )
        // And the one that is allowed: every library, hashed, in any order.
        val reordered = installedStatus.copy(
            verifiedSha256 = NOT_A_REAL_ARTIFACT.libraries.map { it.sha256 }.reversed()
        )
        assertTrue(dwAsrMayLoad(reordered))
    }

    @Test
    fun `only a fully verified installed status may load`() {
        assertTrue(dwAsrMayLoad(installedStatus))
        // Every other state, however much was verified, is a no.
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

    // ---------------------------------------------------------------------------------------
    // The artifact record — the trust boundary refuses to hold anything it cannot check
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an artifact cannot be served over plain http`() {
        // This is executable code that will run in the process holding Aadhaar numbers. There is no
        // code path in the app that could use a cleartext URL, because the type will not hold one.
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
        /*
         * THE TRAVERSAL THIS PREVENTS. These names address files inside the app's own internal
         * storage; an entry like `../databases/workshop.db` would put fetched bytes over the database
         * holding a fortnight of unsynced fieldwork. The check is in the TYPE rather than in the
         * unpacking code, so it holds for every future caller and not only the one written today.
         */
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
        // And a name that is not a native library at all: `System.load` would not open it, so an
        // artifact describing one is describing the wrong thing.
        assertTrue(
            runCatching { DwAsrLibrary("engine.bin", digest('b'), 1L) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    @Test
    fun `an artifact's ABI is checked to be a bare name, because it is also a directory`() {
        /*
         * THE ASYMMETRY THIS CLOSES. `DwAsrLibrary.fileName` was guarded against separators and `..`
         * from the first draft; `abi` was not, on the argument that a constant compiled into the APK
         * cannot be a path fragment — which is not an argument, it is a restatement. The Android half
         * builds `filesDir/asr-engine/<abi>/` out of this value and names the downloaded container
         * `engine-<abi>.zip`, so it is the second of exactly two path fragments in this feature and it
         * gets the same check as the first. A release builder's typo, not an attack, and refused with
         * the same sentence either way.
         */
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
        // Carried as a constructor parameter, it could say 24 MB beside four libraries totalling 34,
        // and the storage gate would pass on a phone the install then filled.
        assertEquals(24L * mib, NOT_A_REAL_ARTIFACT.installedBytes)
        assertEquals(
            24L * mib + 20L * mib + DW_ASR_FREE_STORAGE_MARGIN_BYTES,
            dwAsrStorageNeededBytes(NOT_A_REAL_ARTIFACT)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Which engine this handset gets
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the handset's own ABI order decides, not the order rows were written in`() {
        /*
         * THE DEFECT THIS PINS. `Build.SUPPORTED_ABIS` is ordered primary-first, so a 64-bit phone
         * that also runs 32-bit code lists arm64-v8a first and must get the 64-bit engine. Selecting
         * with `catalogue.firstOrNull { it.abi in abis }` reads almost identically and would hand a
         * 64-bit handset the 32-bit engine whenever the armeabi-v7a row happened to be typed first.
         */
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
        // Two different facts. An empty ABI list is a failed read, not a handset with no processors,
        // and reading it as "no build for you" would be a verdict invented from a missing answer.
        assertEquals(
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR,
            offer(measurement = roomyPhone.copy(abis = listOf("x86_64")))
        )
        assertEquals(
            DwAsrOffer.PROCESSOR_UNMEASURED,
            offer(measurement = roomyPhone.copy(abis = emptyList()))
        )
        // The sentence for the first names the builds that DO exist, so a designer reading it down a
        // phone line can say more than "not mine".
        val sentence = dwAsrOfferSentence(
            DwAsrOffer.NO_BUILD_FOR_THIS_PROCESSOR,
            roomyPhone.copy(abis = listOf("x86_64")),
            invented,
        )
        assertTrue(sentence.contains("x86_64"))
        assertTrue(sentence.contains("arm64-v8a"))
    }

    // ---------------------------------------------------------------------------------------
    // Storage, and the order the refusals come in
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unmeasured storage reading refuses rather than passing`() {
        // A missing StatFs answer is not "plenty" and is not "none". A 24 MB fetch started on a
        // question nobody answered is the one that dies at 98% having spent the bundle to get there.
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
        // 100 MiB printed in the decimal units a data bundle is sold in.
        assertTrue("the designer needs the number, not just the verdict", sentence.contains("105 MB"))
    }

    @Test
    fun `the model refusal comes before storage but after the processor, following dwTier2Offer`() {
        /*
         * THE ORDERING ARGUMENT, PINNED SO IT CANNOT BE TIDIED AWAY.
         *
         *  - A handset with no build for its processor will still have none after a model is measured,
         *    so it hears the durable truth about itself rather than a roadmap it is not on. That is
         *    `dwTier2Offer`'s own argument for telling a low-memory phone it is too small BEFORE
         *    telling it nothing has been weighed.
         *  - A handset merely short of storage COULD take the engine, so telling it to delete a day's
         *    photographs to make room for something that would not work anyway is the worse sentence.
         */
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
        /*
         * THE ORDERING GOT WRONG ONCE ON THE WAY HERE, WHICH IS WHY THIS TEST EXISTS. With the UNKNOWN
         * check first, a build with nothing published told every designer "this app could not look at
         * its own files" — a true sentence about an irrelevant question, printed instead of the one
         * that explains why the card is disabled.
         */
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
        // With something published, an unread disk is honestly unknown and claims nothing either way.
        assertEquals(DwAsrOffer.UNKNOWN, offer(status = DwAsrRuntimeStatus()))
        assertFalse(dwAsrMayInstall(DwAsrOffer.UNKNOWN))
    }

    // ---------------------------------------------------------------------------------------
    // The connection, and paying twice
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no connection never draws a control, however installable the engine is`() {
        assertEquals(DwAsrOffer.NO_CONNECTION, offer(connection = DwConnection.NONE))
        assertFalse(dwAsrMayInstall(DwAsrOffer.NO_CONNECTION))
        // Metered is a WARNING attached to a deliberate tap, not a refusal: a designer in a district
        // town with no Wi-Fi for a fortnight would otherwise never be able to install it at all.
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
        // Every failure path deletes what it produced, so there is nothing on the phone to keep and
        // nothing to pay for again — and the note beside the button says to try again, which needs a
        // button to do it with.
        val failed = DwAsrRuntimeStatus(
            DwAsrRuntimeState.FAILED,
            NOT_A_REAL_ARTIFACT,
            failure = DW_ASR_MISMATCH_SENTENCE,
        )
        assertEquals(DwAsrOffer.RETRY, offer(status = failed))
        assertTrue(dwAsrMayInstall(DwAsrOffer.RETRY))
        // But not where the phone could not carry it anyway.
        assertEquals(DwAsrOffer.NO_CONNECTION, offer(status = failed, connection = DwConnection.NONE))
    }

    @Test
    fun `exactly two offers may ever draw a control, and both need a connection`() {
        // The invariant behind "nothing auto-downloads and nothing offers what it cannot do".
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

    // ---------------------------------------------------------------------------------------
    // The size — the one figure this app is allowed to print
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the engine sizes are the ones measured off the packaged APK, per ABI`() {
        /*
         * QUOTED, NOT INVENTED. docs/ASR-RUNTIME-MEASUREMENT.md, "the delta per ABI, off the APKs'
         * central directories", row A → row E (the static-linked AAR, that document's recommendation
         * 2): arm64-v8a 11,074,640 → 34,721,464 and armeabi-v7a 6,789,192 → 22,941,324. Every `lib/`
         * entry there is STORED at minSdk 26 with extractNativeLibs="false", so these are the bytes on
         * the phone as well as in the archive.
         */
        assertEquals(34_721_464L - 11_074_640L, DW_ASR_ENGINE_BYTES_ARM64)
        assertEquals(22_941_324L - 6_789_192L, DW_ASR_ENGINE_BYTES_ARM32)
        // And they are different numbers on the screen, which is the reason this is per-ABI at all.
        assertEquals("24 MB", dwBytesLabel(DW_ASR_ENGINE_BYTES_ARM64))
        assertEquals("16 MB", dwBytesLabel(DW_ASR_ENGINE_BYTES_ARM32))
    }

    @Test
    fun `the size is named before the tap, per processor, unlike a Google pack`() {
        /*
         * THE ONE PLACE THIS APP CAN NAME A SIZE. `dwDownloadCostSentence` refuses to print one
         * because `triggerModelDownload` reports none and any figure would be invented; our artifact
         * has been weighed, so plan §2.1's "show the real size" applies here and nowhere else.
         */
        val arm64 = dwAsrCostSentence(roomyPhone, DwConnection.METERED)
        assertTrue(arm64.contains("24 MB"))
        assertTrue("the processor is named, because the number depends on it", arm64.contains("arm64-v8a"))
        assertTrue("the meter warning comes before the tap", arm64.contains("mobile data"))

        val arm32 = dwAsrCostSentence(roomyPhone.copy(abis = listOf("armeabi-v7a")), DwConnection.UNMETERED)
        assertTrue(arm32.contains("16 MB"))
        assertFalse("a v7a handset must not be shown the arm64 figure", arm32.contains("24 MB"))

        // A published artifact's own size wins over the measurement, once one exists.
        val pinned = dwAsrCostSentence(roomyPhone, DwConnection.UNMETERED, NOT_A_REAL_ARTIFACT)
        assertTrue(pinned.contains(dwBytesLabel(NOT_A_REAL_ARTIFACT.downloadBytes)))
    }

    @Test
    fun `an unmeasured processor is told the word unmeasured, never given the ARM figure`() {
        // x86 and x86_64 are marked unmeasured in the measurement document, deliberately. An emulator
        // asking what the engine weighs gets the word rather than a number borrowed from ARM.
        assertEquals(null, dwAsrMeasuredEngineBytes(listOf("x86_64", "x86")))
        assertEquals(null, dwAsrMeasuredEngineBytes(emptyList()))
        val sentence = dwAsrCostSentence(roomyPhone.copy(abis = listOf("x86_64")), DwConnection.UNMETERED)
        assertTrue(sentence.contains("unmeasured"))
        assertFalse(Regex("""\d+\s*MB""").containsMatchIn(sentence))
        // And a handset that would not say what processor it has is told that instead of a size.
        val silent = dwAsrCostSentence(roomyPhone.copy(abis = emptyList()), DwConnection.NONE)
        assertTrue(silent.contains("unmeasured"))
    }

    @Test
    fun `the processor named beside the size is the one the size was measured for`() {
        /*
         * THE MIXED ABI LIST, WHICH THE TEST ABOVE DOES NOT COVER AND A REAL HANDSET REPORTS. An
         * ARC / Houdini device lists `[x86_64, x86, armeabi-v7a]` and runs this app's 32-bit code, so
         * the engine it would fetch IS the armeabi-v7a one and 16 MB is the right number for it — but
         * built from `abis.first()`, the sentence read "on this phone's processor (x86_64) the engine
         * is 16 MB", attaching an ARM measurement to the one processor
         * docs/ASR-RUNTIME-MEASUREMENT.md marks unmeasured. The number was right and the sentence was
         * a fabrication.
         */
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
        // A pinned artifact names its own ABI, which is the ABI its digests belong to.
        val pinned = dwAsrCostSentence(translated, DwConnection.UNMETERED, NOT_A_REAL_ARM32_ARTIFACT)
        assertTrue(pinned.contains("armeabi-v7a"))
        // And nothing measured at all still names the processor whose size is unknown, truthfully.
        assertEquals(null, dwAsrMeasuredAbi(listOf("x86_64", "x86")))
        assertTrue(
            dwAsrCostSentence(roomyPhone.copy(abis = listOf("x86_64", "x86")), DwConnection.NONE)
                .contains("(x86_64) is unmeasured")
        )
    }

    // ---------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------

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
            assertTrue("and long enough to say what to do: $sentence", sentence.length > 60)
        }
        // The short labels likewise: two states behind one label read as one state.
        val labels = DwAsrRuntimeState.entries.map { dwAsrStateLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `no sentence promises the engine works today, and the pitch does not promise Odia`() {
        /*
         * THE MISLEADING THIS FEATURE MUST NOT DO. The blurb may say what Android's packs do NOT
         * carry — that is measured, off the fleet's own M32 — but it must not promise that this
         * engine transcribes Odia, because how well it does that is unmeasured and plan §2.2 makes
         * measuring it the bar before offering it at all.
         */
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
        // And what it buys is stated conditionally on the model, never as something already true.
        assertTrue(DW_ASR_WHAT_IT_BUYS.startsWith("With the engine and a language model"))
    }

    @Test
    fun `the pitch does not leave a designer believing an engine is a download away`() {
        /*
         * WHY THIS IS PINNED SEPARATELY FROM THE TEST ABOVE. [DW_ASR_OFFER_BLURB] is the WHOLE of the
         * collapsed first-run card: the state, the size and DW_ASR_ENGINE_WITHOUT_A_VOICE appear only
         * after "What would it cost?" is tapped, because the collapsed shape exists to keep a device
         * probe and a 24 MB hash off the dashboard's first frame. So for a designer who reads the card
         * and taps "Not now", this paragraph is everything the app ever told them — and it used to end
         * at "This app can install a speech engine of its own to close that gap", which reads as an
         * available download. Nothing is published and no model has been measured.
         *
         * The clause is asserted for what it must SAY, not for its exact words, and it must be a
         * standing precondition rather than today's state — a paragraph saying "nothing is published
         * yet" would become a lie the day a row is pinned, which is the rot this repository writes
         * keep rules against.
         */
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
        // The expanded card's own sentences are the ones that carry today's answer, and they say it.
        assertTrue(DW_ASR_ENGINE_WITHOUT_A_VOICE.contains("nothing can be installed yet"))
        // The settings card has its OWN blurb — the KDoc on this one used to claim both surfaces read
        // it verbatim, and only the first-run card does.
        assertTrue(DW_ASR_CARD_BLURB != DW_ASR_OFFER_BLURB)
        assertTrue(
            "the settings blurb promises nothing either",
            DW_ASR_CARD_BLURB.contains("It is optional")
        )
    }

    @Test
    fun `the verification sentence claims integrity and not provenance`() {
        /*
         * WHAT THE DIGEST ESTABLISHES IS EXACTLY: THIS IS THE FILE THE RELEASE BUILDER PINNED. It is
         * not a signature and says nothing about upstream. A designer sentence that implied the engine
         * had been audited, or that it "comes from" anybody in particular, would be claiming more
         * assurance than exists — which is the failure this whole repository documents against.
         */
        assertTrue(DW_ASR_VERIFY_SENTENCE.contains("fingerprint built into this app"))
        assertTrue(DW_ASR_VERIFY_SENTENCE.contains("thrown away if it does not match"))
        listOf("signed", "signature", "audited", "certified", "official", "safe").forEach { claim ->
            assertFalse(
                "the verification sentence must not claim “$claim”",
                DW_ASR_VERIFY_SENTENCE.contains(claim, ignoreCase = true)
            )
        }
        // The mismatch sentence says what happened to the bytes and when to stop trying.
        assertTrue(DW_ASR_MISMATCH_SENTENCE.contains("deleted"))
        assertTrue(DW_ASR_MISMATCH_SENTENCE.contains("twice"))
    }
}
