package com.designprototype.workshop.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The banner stack's footprint: added to a page's end while it shows, nothing once it is gone. */
class BannerClearanceTest {

    @Test
    fun `a showing stack reserves its height plus the gap`() {
        assertEquals(60.dp + BANNER_STACK_GAP, bannerClearance(60.dp, shown = true))
    }

    @Test
    fun `a page keeps no hole for a banner that is gone`() {
        assertEquals(0.dp, bannerClearance(60.dp, shown = false))
        assertEquals(0.dp, bannerClearance(0.dp, shown = true))
    }

    @Test
    fun `a page's end clears the island bar and the banners above it`() {
        assertEquals(140.dp, pageBottomReserve(islandReserve = 80.dp, bannerClearance = 60.dp))
    }
}
