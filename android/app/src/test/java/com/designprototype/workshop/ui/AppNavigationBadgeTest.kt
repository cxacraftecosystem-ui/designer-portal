package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationBadgeTest {

    @Test
    fun `only the access roster wears the pending count`() {
        NavDestination.entries.forEach { destination ->
            val entry = FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }
                ?: return@forEach
            val badge = navBadge(entry, pendingAccessCount = 7)
            if (destination == NavDestination.ACCESS_ROSTER) {
                assertEquals("the pending count must reach “${entry.label}”", "7", badge)
            } else {
                assertNull("“${entry.label}” must not wear the access-roster count", badge)
            }
        }
    }

    @Test
    fun `nothing pending draws no badge`() {
        val roster = FIELD_NAV_ITEMS.first { it.destination == NavDestination.ACCESS_ROSTER }
        assertNull("a zero must not be drawn as a badge", navBadge(roster, pendingAccessCount = 0))
        assertNull("nor may a sentinel", navBadge(roster, pendingAccessCount = -1))
        assertEquals("but one pending request is a badge", "1", navBadge(roster, pendingAccessCount = 1))
    }

    @Test
    fun `only workshops to inspect wears the inspection count`() {
        NavDestination.entries.forEach { destination ->
            val entry = FIELD_NAV_ITEMS.firstOrNull { it.destination == destination } ?: return@forEach
            val badge = navBadge(entry, pendingAccessCount = 0, awaitingInspectionCount = 4)
            if (destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS) {
                assertEquals("4", badge)
            } else {
                assertNull("“${entry.label}” must not wear the inspection count", badge)
            }
        }
        val inspections = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS }
        assertNull(navBadge(inspections, pendingAccessCount = 0, awaitingInspectionCount = 0))
    }
}
