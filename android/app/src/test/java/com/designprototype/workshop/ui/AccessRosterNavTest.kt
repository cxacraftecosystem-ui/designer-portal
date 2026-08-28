package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NOTIFICATION: that it reaches an admin, that it reaches nobody else, and that it never lies.
 *
 * The requirement asks that admins and master admins be told when somebody is turned away at the
 * sign-in screen. Neither application has an email sender or a push transport, so the notification
 * is a COUNT on chrome an admin already opens — which makes these three properties the whole of the
 * feature's delivery guarantee, and all three are the kind that go quietly wrong:
 *
 * * the entry is offered to admins only, because `GET /access/roster` is `require_access_manager`
 *   for READS as much as writes — the queue is a list of named people who tried to get in;
 * * the badge lands on that entry and on no other, which is the thing a copy-paste breaks;
 * * a zero draws NOTHING. "0" over a queue that failed to load is a lie an admin acts on by not
 *   opening it, and the count is fetched by a best-effort poll that keeps its last value when the
 *   phone has no signal.
 */
class AccessRosterNavTest {

    private fun user(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "INSPECTOR",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    private val entry = FIELD_NAV_ITEMS.first { it.destination == NavDestination.ACCESS_ROSTER }

    @Test
    fun `who may sign in is offered to admins and above, and to nobody else`() {
        val offered = everyRole.filter { FieldPermissions.canManageAccessRoster(user(it)) }.toSet()
        assertEquals(
            "canManageAccessRoster must equal deps.can_manage_access_roster — is_admin and above",
            setOf("ADMIN", "MASTER_ADMIN"),
            offered
        )
    }

    @Test
    fun `the entry exists, is admin chrome, and mirrors the server predicate it is named after`() {
        assertEquals("require_access_manager", entry.gate)
        assertTrue(
            "an admin browsing as an ordinary user must not carry a list of named strangers around",
            entry.adminSurface
        )
        // The predicate on the entry is what actually decides; the gate string is documentation.
        assertTrue(entry.can(user("MASTER_ADMIN")))
        assertTrue(entry.can(user("ADMIN")))
        assertFalse(entry.can(user("PROFESSOR")))
        assertFalse(entry.can(user("DESIGNER")))
    }

    @Test
    fun `an admin sees it only while admin view is on`() {
        assertTrue(isNavItemVisible(entry, user("ADMIN"), adminMode = true))
        assertFalse(isNavItemVisible(entry, user("ADMIN"), adminMode = false))
        // Entitlement first, admin view second: the toggle can only ever subtract.
        assertFalse(isNavItemVisible(entry, user("RESEARCHER"), adminMode = true))
    }

    @Test
    fun `the badge lands on the allow-list entry and on nothing else`() {
        assertEquals("3", navBadge(entry, 3))
        val elsewhere = FIELD_NAV_ITEMS
            .filter { it.destination != NavDestination.ACCESS_ROSTER }
            .mapNotNull { navBadge(it, 3)?.let { badge -> "${it.label}=$badge" } }
        assertEquals(
            "a count on any other menu row is a number nobody can act on: $elsewhere",
            emptyList<String>(),
            elsewhere
        )
    }

    @Test
    fun `an empty queue draws no badge at all`() {
        // Zero also covers "the poll could not ask", which is why it must not render. A confident
        // "0" over an unread queue is worse than no badge: it is an answer, and it is wrong.
        assertNull(navBadge(entry, 0))
        assertNull(navBadge(entry, -1))
        assertNotNull(navBadge(entry, 1))
    }

    @Test
    fun `the label is the one an admin is looking for, and is not the designer roster's`() {
        // Both lists are reachable from this menu and they read alike; the labels are the only thing
        // separating "may this person reach the app at all" from "is this person empanelled".
        val designerRoster = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGNER_ROSTER }
        assertEquals("Who may sign in", entry.label)
        assertEquals("Designer roster", designerRoster.label)
    }
}
