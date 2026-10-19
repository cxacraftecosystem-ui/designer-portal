package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AddressDistrictsDto
import com.designprototype.workshop.data.AddressReferenceDto
import com.designprototype.workshop.data.LocationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fresh install that goes offline before it ever connects must still be able to save an artisan.
 *
 * The 0.0.19 emulator smoke caught the opposite: states came from the compiled-in list, districts
 * from nowhere, and `artisanLocationRequirementError` refused every new record. The APK now ships
 * the server's whole address reference as a floor, and these hold the floor to that job.
 */
class AddressReferenceBaselineTest {

    private fun asset(): File {
        val relative = listOf(
            "src/main/assets/$ADDRESS_REFERENCE_ASSET",
            "app/src/main/assets/$ADDRESS_REFERENCE_ASSET",
            "android/app/src/main/assets/$ADDRESS_REFERENCE_ASSET",
        )
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("$ADDRESS_REFERENCE_ASSET not found from ${File(".").absolutePath}")
    }

    private val shipped: AddressReferenceDto by lazy {
        val decoded = AddressReferenceBaseline.decode(asset().readText(Charsets.UTF_8))
        assertNotNull("the shipped baseline does not decode", decoded)
        decoded!!
    }

    private fun reference(version: Int, districts: Map<String, List<String>>?) = AddressReferenceDto(
        version = version,
        statesAndUnionTerritories = listOf("Rajasthan"),
        districts = districts?.let { AddressDistrictsDto(byState = it) }
    )

    @Test
    fun `the shipped baseline offers districts for every state it offers`() {
        val states = shipped.statesAndUnionTerritories
        assertEquals("every state and union territory ships", 36, states.size)
        assertEquals(BUNDLED_STATES, states)
        val byState = shipped.districts?.byState.orEmpty()
        for (state in states) {
            assertTrue("$state has no bundled districts", byState[state].orEmpty().isNotEmpty())
        }
        assertEquals(shipped.districts?.count, byState.values.sumOf { it.size })
    }

    @Test
    fun `a fresh install with no signal can answer both questions a new artisan needs`() {
        val chosen = chooseAddressReference(cached = null, bundled = shipped)!!
        assertTrue(chosen.bundled)
        val place = LocationRequest(
            latitude = 26.82,
            longitude = 75.54,
            state = "Rajasthan",
            district = chosen.reference.districts!!.byState.getValue("Rajasthan").first()
        )
        assertNull(artisanLocationRequirementError(place))
    }

    @Test
    fun `the device cache wins over the baseline when it is as new and carries districts`() {
        val cached = reference(2, mapOf("Rajasthan" to listOf("Balotra")))
        val chosen = chooseAddressReference(cached, reference(2, mapOf("Rajasthan" to listOf("Jaipur"))))!!
        assertFalse(chosen.bundled)
        assertEquals(cached, chosen.reference)
    }

    @Test
    fun `a newer baseline replaces an older cache`() {
        val bundled = reference(3, mapOf("Rajasthan" to listOf("Balotra")))
        val chosen = chooseAddressReference(reference(2, mapOf("Rajasthan" to listOf("Jaipur"))), bundled)!!
        assertTrue(chosen.bundled)
        assertEquals(bundled, chosen.reference)
    }

    @Test
    fun `a cache with states and no districts loses to the baseline`() {
        val bundled = reference(2, mapOf("Rajasthan" to listOf("Balotra")))
        assertTrue(chooseAddressReference(reference(2, null), bundled)!!.bundled)
        assertTrue(chooseAddressReference(reference(1, emptyMap()), bundled)!!.bundled)
    }

    @Test
    fun `no baseline leaves the cache, and nothing at all stays nothing`() {
        val cached = reference(2, null)
        assertEquals(cached, chooseAddressReference(cached, null)!!.reference)
        assertNull(chooseAddressReference(null, null))
    }

    @Test
    fun `a broken asset is no floor rather than an empty one`() {
        assertNull(AddressReferenceBaseline.decode("<html>captive portal</html>"))
        assertNull(AddressReferenceBaseline.decode("{\"version\":2}"))
    }

    @Test
    fun `a bundled district list prints no stale-list sentence`() {
        val state = AddressReferenceState(reference = shipped, loading = false, bundled = true)
        assertNull(addressListNotice("districts", 33, state, bundled = state.bundled))
    }
}
