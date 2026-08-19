package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`dwAsrModelOffer`'s DECISION TABLE, WHICH NOTHING ON THE JVM ASKED ABOUT UNTIL NOW.**
 *
 * The nearest thing that existed was `DwAsrModelTransferProbeTest`, on a handset, and
 * `DwAsrModelTest`'s `pinningAModelDidNotMakeAnythingInstallable`, which drives `dwAsrOffer` — the
 * ENGINE's offer, a different function. So the ordering this file's comments call load-bearing — the
 * staged copy beating a fetch — has been enforced by a comment and nothing else.
 *
 * WHAT EACH ASSERTION IS ACTUALLY PROTECTING, because a table test is easy to write and easy to
 * write meaninglessly:
 *
 *   * STAGED BEATS THE ENDPOINT, or a designer whose administrator already pushed the files over a
 *     cable is charged 365 MB to fetch bytes sitting on the phone in front of them. It is exactly
 *     the ordering a later refactor reverses without noticing, because both branches "work".
 *   * UNKNOWN IS NOT "NO", this codebase's standing rule at the one new place it can break.
 *   * THE ENDPOINT IGNORES THE CONTAINER'S UNREADABILITY, which is the assertion that proves the
 *     per-file route DELETES the bzip2 blocker rather than working around it.
 *   * A REFUSAL ON ONE ROUTE DOES NOT SUPPRESS THE OTHER, which is the thing that would silently
 *     break the day the pinned container is republished as a `.zip`.
 */
class DwAsrModelOfferTest {

    /**
     * The fleet's SM-M325F as it read at 02:57 on 2026-08-13 — the same reading `DwSpeechCardProseTest`
     * uses, copied rather than invented so two suites do not disagree about one phone. 39 GB free,
     * which clears every gate below; the storage cases override it explicitly.
     */
    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

    /** The shipped catalogues, so the table is about what this APK actually pins. */
    private val pinned = DW_ASR_MODELS.single()
    private val container = DW_ASR_MODEL_ARTIFACTS.single()

    private fun offer(
        state: DwAsrModelState = DwAsrModelState.NOT_INSTALLED,
        connection: DwConnection = DwConnection.UNMETERED,
        staged: Boolean = false,
        endpoint: DwAsrEndpointState = DwAsrEndpointState.UNKNOWN,
        measurement: DwDeviceMeasurement = fleetHandset,
    ): DwAsrModelOffer = dwAsrModelOffer(
        status = DwAsrModelStatus(state, if (state == DwAsrModelState.UNKNOWN) null else pinned),
        measurement = measurement,
        connection = connection,
        stagedFilesPresent = staged,
        catalogue = DW_ASR_MODELS,
        artifacts = DW_ASR_MODEL_ARTIFACTS,
        endpoint = endpoint,
    )

    // ── The ordering that keeps a designer from paying twice ─────────────────────────────────────

    @Test
    fun `staged files beat the endpoint, whatever the deployment says`() {
        DwAsrEndpointState.values().forEach { state ->
            assertEquals(
                "a phone with the files already on it was offered a fetch, on endpoint=$state",
                DwAsrModelOffer.INSTALL_FROM_STAGED_FILES,
                offer(staged = true, endpoint = state),
            )
        }
    }

    @Test
    fun `the endpoint route ignores the container's unreadability`() {
        // THE ASSERTION THAT PROVES THE BLOCKER IS DELETED RATHER THAN WORKED AROUND. The pinned
        // container is still a `.tar.bz2` and this build still cannot open one; the endpoint serves
        // the two files unpacked, so there is no archive for that to be about.
        assertFalse(
            "this test is meaningless if the pinned container became readable",
            container.container.readableInThisBuild,
        )
        assertEquals(
            DwAsrModelOffer.DOWNLOAD,
            offer(endpoint = DwAsrEndpointState.PUBLISHES),
        )
        assertTrue(dwAsrModelMayInstall(offer(endpoint = DwAsrEndpointState.PUBLISHES)))
    }

    @Test
    fun `an unknown endpoint state is not a published one, and is not a refusal either`() {
        // Not-yet-looked is never rendered as not-there. UNKNOWN falls through to the container
        // branch and gets today's answer, which on this build is the unreadable container.
        assertEquals(
            DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD,
            offer(endpoint = DwAsrEndpointState.UNKNOWN),
        )
        assertNotEquals(DwAsrModelOffer.DOWNLOAD, offer(endpoint = DwAsrEndpointState.UNKNOWN))
        assertFalse(dwAsrModelMayInstall(offer(endpoint = DwAsrEndpointState.UNKNOWN)))
    }

    @Test
    fun `an unreachable or empty deployment falls through to the cable answer`() {
        // These two ARE the ordinary answers on the fleet today — `ASR_MODEL_DIR` is unset by
        // default on the origin — so the sentence a designer reads has to be the container one,
        // which names the cable. See dwAsrModelEndpointRefusal on why they return no refusal of
        // their own.
        listOf(DwAsrEndpointState.UNREACHABLE, DwAsrEndpointState.NOT_PUBLISHED).forEach { state ->
            assertEquals(
                DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD,
                offer(endpoint = state),
            )
        }
    }

    // ── The three refusals that are more use than "this app cannot open a .tar.bz2" ──────────────

    @Test
    fun `an account or session problem is said in its own words and draws no button`() {
        assertEquals(DwAsrModelOffer.NOT_ENTITLED, offer(endpoint = DwAsrEndpointState.FORBIDDEN))
        assertEquals(
            DwAsrModelOffer.SESSION_LAPSED,
            offer(endpoint = DwAsrEndpointState.SESSION_LAPSED),
        )
        assertEquals(
            DwAsrModelOffer.DEPLOYMENT_DOES_NOT_KNOW_THIS_MODEL,
            offer(endpoint = DwAsrEndpointState.VERSION_SKEW),
        )
        // No button on any of them: a control that cannot work is worse than an absent one.
        listOf(
            DwAsrEndpointState.FORBIDDEN,
            DwAsrEndpointState.SESSION_LAPSED,
            DwAsrEndpointState.VERSION_SKEW,
        ).forEach { state ->
            assertFalse(
                "a button was drawn for a refusal the designer cannot tap through: $state",
                dwAsrModelMayInstall(offer(endpoint = state)),
            )
        }
    }

    @Test
    fun `a refusal on the endpoint never suppresses a container route that would have worked`() {
        /*
          LATENT TODAY AND ASSERTED ANYWAY, because it is latent only while the pinned container is
          unreadable. `dwAsrModelEndpointRefusal` is asked ONLY at the container branch's dead ends;
          if it were asked before them, a 403 on THIS deployment would refuse an install that the
          pinned container could have served, and the day somebody republishes the model as a `.zip`
          that becomes a live defect nobody would connect to this change.

          The fixture republishes it here rather than waiting for that day.
         */
        val republished = listOf(container.copy(container = DwAsrContainerFormat.ZIP))
        listOf(
            DwAsrEndpointState.FORBIDDEN,
            DwAsrEndpointState.SESSION_LAPSED,
            DwAsrEndpointState.VERSION_SKEW,
            DwAsrEndpointState.NOT_PUBLISHED,
            DwAsrEndpointState.UNREACHABLE,
            DwAsrEndpointState.UNKNOWN,
        ).forEach { state ->
            assertEquals(
                "the container route was suppressed by an endpoint answer: $state",
                DwAsrModelOffer.DOWNLOAD,
                dwAsrModelOffer(
                    status = DwAsrModelStatus(DwAsrModelState.NOT_INSTALLED, pinned),
                    measurement = fleetHandset,
                    connection = DwConnection.UNMETERED,
                    stagedFilesPresent = false,
                    catalogue = DW_ASR_MODELS,
                    artifacts = republished,
                    endpoint = state,
                ),
            )
        }
    }

    // ── The gates below the route choice ─────────────────────────────────────────────────────────

    @Test
    fun `the endpoint route is refused with no connection, and says so rather than drawing a button`() {
        val answer = offer(endpoint = DwAsrEndpointState.PUBLISHES, connection = DwConnection.NONE)
        assertEquals(DwAsrModelOffer.NO_CONNECTION, answer)
        assertFalse(dwAsrModelMayInstall(answer))
    }

    @Test
    fun `the endpoint route is sized against the model alone, not the model plus a container`() {
        /*
          THE NUMBER THAT SEPARATES THE TWO ROUTES. The container route needs the archive and the
          unpacked files on the phone AT ONCE — 292,571,207 + 365,438,543 + the 1 GiB margin — and
          the endpoint route writes the model once. A phone with room for one and not the other is
          the case that proves the branch is sized against what it actually writes; charging the
          endpoint the container's question would refuse an install that would have fitted.
         */
        val room = dwAsrModelStorageNeededBytes(pinned) + 1L
        assertTrue(
            "the fixture must not fit the container route, or it proves nothing",
            room < dwAsrModelStorageNeededBytes(pinned, container),
        )
        val tight = fleetHandset.copy(freeStorageBytes = room)
        assertEquals(
            DwAsrModelOffer.DOWNLOAD,
            offer(endpoint = DwAsrEndpointState.PUBLISHES, measurement = tight),
        )
        // And below the model's own requirement it is refused, on both routes.
        val tooTight = fleetHandset.copy(freeStorageBytes = dwAsrModelStorageNeededBytes(pinned) - 1L)
        assertEquals(
            DwAsrModelOffer.NOT_ENOUGH_STORAGE,
            offer(endpoint = DwAsrEndpointState.PUBLISHES, measurement = tooTight),
        )
        assertEquals(
            DwAsrModelOffer.NOT_ENOUGH_STORAGE,
            offer(staged = true, measurement = tooTight),
        )
    }

    @Test
    fun `a paused fetch is still a resume, whatever the deployment now says`() {
        // Bytes already paid for outrank every gate below, which is why PAUSED is answered at step 2
        // before `endpoint` is consulted at all. That is also why a resume cannot be attributed to a
        // route by asking the deployment — see [dwAsrModelSourceFor] and the controller's
        // `pausedSource`, which reads the disk instead.
        DwAsrEndpointState.values().forEach { state ->
            assertEquals(
                DwAsrModelOffer.RESUME,
                offer(state = DwAsrModelState.PAUSED, endpoint = state),
            )
        }
    }

    @Test
    fun `a failed attempt on the endpoint route is a retry rather than a fresh download`() {
        assertEquals(
            DwAsrModelOffer.RETRY,
            offer(state = DwAsrModelState.FAILED, endpoint = DwAsrEndpointState.PUBLISHES),
        )
    }

    // ── Which route the tap would actually take ──────────────────────────────────────────────────

    @Test
    fun `the source is derived once and matches the branch the offer took`() {
        assertEquals(
            DwAsrModelSource.STAGED_FILES,
            dwAsrModelSourceFor(
                DwAsrModelOffer.INSTALL_FROM_STAGED_FILES,
                DwAsrEndpointState.PUBLISHES,
                stagedFilesPresent = true,
            ),
        )
        assertEquals(
            DwAsrModelSource.DEPLOYMENT_ENDPOINT,
            dwAsrModelSourceFor(DwAsrModelOffer.DOWNLOAD, DwAsrEndpointState.PUBLISHES),
        )
        assertEquals(
            DwAsrModelSource.PINNED_CONTAINER,
            dwAsrModelSourceFor(DwAsrModelOffer.DOWNLOAD, DwAsrEndpointState.UNKNOWN),
        )
        // A RESUME takes the DISK's answer over the deployment's, because the prefix was written by
        // whichever route ran last and the deployment may have been provisioned since.
        assertEquals(
            DwAsrModelSource.PINNED_CONTAINER,
            dwAsrModelSourceFor(
                DwAsrModelOffer.RESUME,
                DwAsrEndpointState.PUBLISHES,
                pausedSource = DwAsrModelSource.PINNED_CONTAINER,
            ),
        )
        // Nothing that moves no bytes claims a route.
        listOf(
            DwAsrModelOffer.ALREADY_INSTALLED,
            DwAsrModelOffer.NOTHING_PINNED,
            DwAsrModelOffer.NOT_ENTITLED,
            DwAsrModelOffer.UNKNOWN,
        ).forEach {
            assertEquals(null, dwAsrModelSourceFor(it, DwAsrEndpointState.PUBLISHES))
        }
    }

    // ── The money on the line a designer reads ───────────────────────────────────────────────────

    @Test
    fun `the endpoint route quotes its own wire cost and not the container's smaller one`() {
        val endpointSentence = dwAsrModelOfferSentence(
            DwAsrModelOffer.DOWNLOAD,
            fleetHandset,
            source = DwAsrModelSource.DEPLOYMENT_ENDPOINT,
        )
        val containerSentence = dwAsrModelOfferSentence(
            DwAsrModelOffer.DOWNLOAD,
            fleetHandset,
            source = DwAsrModelSource.PINNED_CONTAINER,
        )
        /*
          365,438,543 UNPACKED AGAINST 292,571,207 COMPRESSED — the endpoint route is +72,867,336 on
          the wire, about 25% MORE than the container it replaces. This app's rule is that the figure
          a designer reads before spending a prepaid bundle is the real one (`dwDownloadCostSentence`
          sits immediately above the button because "it is the money"), so carrying the container's
          smaller number onto the endpoint route would understate it by 73 MB.

          Asserted through `dwBytesLabel` rather than against a transcribed string, so the test says
          "the model's own size" rather than "365 MB" and cannot drift from the label's rounding.
          (`dwBytesLabel` divides by 1000, not 1024, so 365,438,543 renders as 365 MB — the 349 that
          used to stand here was the MiB value, which nothing in this app ever prints.)
         */
        assertTrue(
            "the endpoint sentence does not name what it will actually fetch: $endpointSentence",
            endpointSentence.contains(dwBytesLabel(pinned.onDiskBytes)),
        )
        assertFalse(
            "the container's smaller figure reached the endpoint sentence: $endpointSentence",
            endpointSentence.contains(dwBytesLabel(container.downloadBytes)),
        )
        assertTrue(
            "the container sentence lost its own figure: $containerSentence",
            containerSentence.contains(dwBytesLabel(container.downloadBytes)),
        )
    }

    @Test
    fun `a storage refusal quotes the figure its own route's gate used`() {
        val tooTight = fleetHandset.copy(freeStorageBytes = 1L)
        val onEndpoint = dwAsrModelOfferSentence(
            DwAsrModelOffer.NOT_ENOUGH_STORAGE,
            tooTight,
            source = DwAsrModelSource.DEPLOYMENT_ENDPOINT,
        )
        val onContainer = dwAsrModelOfferSentence(
            DwAsrModelOffer.NOT_ENOUGH_STORAGE,
            tooTight,
            source = DwAsrModelSource.PINNED_CONTAINER,
        )
        // The container route holds the archive AND the unpacked files at once; the endpoint route
        // writes the model once. A refusal quoting the smaller figure while the gate used the larger
        // one tells a designer to free 365 MB and then refuses them again.
        assertTrue(onEndpoint.contains(dwBytesLabel(pinned.onDiskBytes)))
        assertTrue(
            "the container's refusal understates what its gate asked for: $onContainer",
            onContainer.contains(dwBytesLabel(pinned.onDiskBytes + container.downloadBytes)),
        )
    }

    @Test
    fun `every offer value has a sentence and no sentence names a file in this repository`() {
        // THE EXHAUSTIVE `when` IS THE MECHANISM, and this is the test that notices when somebody
        // satisfies it with an `else`. `DwSpeechCardProseTest`'s rule — no sentence this app shows a
        // designer names a file in this repository — is enforced here for these strings, which that
        // sweep does not walk; the CONTAINER_NOT_READABLE arm printed "docs/ASR-MODEL-SIDELOAD.md"
        // for as long as it existed.
        val repositoryPath = Regex("""\bdocs/[A-Za-z0-9._-]+""")
        DwAsrModelOffer.values().forEach { value ->
            val sentence = dwAsrModelOfferSentence(value, fleetHandset)
            assertTrue("$value has no sentence", sentence.isNotBlank())
            assertFalse(
                "$value names a file in this repository on a designer's card: $sentence",
                repositoryPath.containsMatchIn(sentence),
            )
        }
    }
}
