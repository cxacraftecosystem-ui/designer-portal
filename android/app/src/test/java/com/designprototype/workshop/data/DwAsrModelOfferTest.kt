package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAsrModelOfferTest {

    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

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
        assertEquals(
            DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD,
            offer(endpoint = DwAsrEndpointState.UNKNOWN),
        )
        assertNotEquals(DwAsrModelOffer.DOWNLOAD, offer(endpoint = DwAsrEndpointState.UNKNOWN))
        assertFalse(dwAsrModelMayInstall(offer(endpoint = DwAsrEndpointState.UNKNOWN)))
    }

    @Test
    fun `an unreachable or empty deployment falls through to the cable answer`() {
        listOf(DwAsrEndpointState.UNREACHABLE, DwAsrEndpointState.NOT_PUBLISHED).forEach { state ->
            assertEquals(
                DwAsrModelOffer.CONTAINER_NOT_READABLE_IN_THIS_BUILD,
                offer(endpoint = state),
            )
        }
    }

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

    @Test
    fun `the endpoint route is refused with no connection, and says so rather than drawing a button`() {
        val answer = offer(endpoint = DwAsrEndpointState.PUBLISHES, connection = DwConnection.NONE)
        assertEquals(DwAsrModelOffer.NO_CONNECTION, answer)
        assertFalse(dwAsrModelMayInstall(answer))
    }

    @Test
    fun `the endpoint route is sized against the model alone, not the model plus a container`() {
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
        assertEquals(
            DwAsrModelSource.PINNED_CONTAINER,
            dwAsrModelSourceFor(
                DwAsrModelOffer.RESUME,
                DwAsrEndpointState.PUBLISHES,
                pausedSource = DwAsrModelSource.PINNED_CONTAINER,
            ),
        )
        listOf(
            DwAsrModelOffer.ALREADY_INSTALLED,
            DwAsrModelOffer.NOTHING_PINNED,
            DwAsrModelOffer.NOT_ENTITLED,
            DwAsrModelOffer.UNKNOWN,
        ).forEach {
            assertEquals(null, dwAsrModelSourceFor(it, DwAsrEndpointState.PUBLISHES))
        }
    }

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
        assertTrue(onEndpoint.contains(dwBytesLabel(pinned.onDiskBytes)))
        assertTrue(
            "the container's refusal understates what its gate asked for: $onContainer",
            onContainer.contains(dwBytesLabel(pinned.onDiskBytes + container.downloadBytes)),
        )
    }

    @Test
    fun `every offer value has a sentence and no sentence names a file in this repository`() {
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
