package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxUnfiledSentinelTest {

    @Test
    fun `a box with something in it has no absence to explain`() {
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "dw_9", hadOptions = true))
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "", hadOptions = false))
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "dw_1", hadOptions = false))
    }

    @Test
    fun `a person who empties a filled box is heard even when the list behind it is empty`() {
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = false),
        )
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = true),
        )
    }

    @Test
    fun `a box that was never filled and had a list to offer is a decision`() {
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = true),
        )
    }

    @Test
    fun `a box that was never filled and had nothing to offer is not a decision`() {
        assertEquals(
            UNFILED_NO_OPTIONS,
            unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false),
        )
    }

    @Test
    fun `only the chosen absence becomes a clearance on the replay`() {
        val chosen = pending(
            workshopUnfiledReasons(
                designWorkshop = unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = false),
            )
        )
        val nothingOnOffer = pending(
            workshopUnfiledReasons(
                designWorkshop = unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false),
            )
        )

        assertEquals(setOf("designWorkshopId"), chosen.clearedLinkKeys)
        assertEquals(emptySet<String>(), chosen.emptyPickerKeys)
        assertEquals(emptySet<String>(), nothingOnOffer.clearedLinkKeys)
        assertEquals(setOf("designWorkshopId"), nothingOnOffer.emptyPickerKeys)
    }

    @Test
    fun `the two columns are spelled the way the server spells them`() {
        val both = workshopUnfiledReasons(
            designWorkshop = UNFILED_BY_CHOICE,
            workshop = UNFILED_BY_CHOICE,
        )
        assertEquals(WORKSHOP_LINK_KEYS, both.keys)
        assertTrue(both.keys.all { it in REFERENCE_FIELD_NOUNS })
    }

    @Test
    fun `a form that mounts one picker cannot unfile the column it never showed anybody`() {
        val one = workshopUnfiledReasons(designWorkshop = UNFILED_BY_CHOICE)
        assertEquals(mapOf("designWorkshopId" to UNFILED_BY_CHOICE), one)
        assertEquals(setOf("designWorkshopId"), pending(one).clearedLinkKeys)

        assertEquals(emptyMap<String, String>(), workshopUnfiledReasons())
        assertEquals(emptySet<String>(), pending(workshopUnfiledReasons()).clearedLinkKeys)
    }

    private fun pending(unfiled: Map<String, String>) = PendingEntry(
        id = "entry-1",
        type = "artisan",
        payloadJson = "{\"name\":\"Giriraj Prasad\"}",
        label = "Giriraj Prasad",
        createdAt = "2026-08-30T10:00:00Z",
        targetId = "ar_1",
        unfiled = unfiled,
    )
}
