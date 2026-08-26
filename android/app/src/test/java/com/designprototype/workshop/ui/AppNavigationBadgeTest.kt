package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The drawer's badge, held to the two rules [navBadge]'s own KDoc names.
 *
 * WHY THIS FILE EXISTS AT ALL. [navBadge] was hoisted out of the drawer and widened to `internal`
 * expressly so this suite could exercise it — and the suite was never written, so from the day that
 * KDoc was typed until 2026-08-26 it promised a guarantee nothing asserted and the widened
 * visibility bought nothing. A named test file that does not exist is worse than no citation: the
 * next reader takes the rule as covered and does not look.
 *
 * The rules are the kind that are obviously right while you are writing them and silently wrong six
 * months later, when a second badge arrives and the condition is copied one entry down. Neither is
 * visible to the compiler, and neither is visible on screen until the wrong row wears a number.
 */
class AppNavigationBadgeTest {

    /**
     * THE COUNT LANDS ON "WHO MAY SIGN IN" AND ON NOTHING ELSE.
     *
     * Swept over every member of [NavDestination] rather than the one that is supposed to match, for
     * the reason the KDoc gives: a badge condition copied one entry down is exactly the mistake this
     * shape invites, and asserting only the positive case cannot see it. A destination added later
     * is covered with no edit here.
     */
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

    /**
     * A ZERO DRAWS NOTHING AT ALL, rather than a badge reading "0".
     *
     * A menu row wearing a zero is a row asking to be opened about nothing, and the drawer is read
     * at a glance in a courtyard. Negative is asserted too because the count arrives from a network
     * read: a repository that answered with a sentinel must not put "-1" beside a menu entry.
     */
    @Test
    fun `nothing pending draws no badge`() {
        val roster = FIELD_NAV_ITEMS.first { it.destination == NavDestination.ACCESS_ROSTER }
        assertNull("a zero must not be drawn as a badge", navBadge(roster, pendingAccessCount = 0))
        assertNull("nor may a sentinel", navBadge(roster, pendingAccessCount = -1))
        assertEquals("but one pending request is a badge", "1", navBadge(roster, pendingAccessCount = 1))
    }
}
