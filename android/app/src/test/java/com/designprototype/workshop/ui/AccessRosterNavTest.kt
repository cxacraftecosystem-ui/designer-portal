package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
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
        assertTrue(entry.can(user("MASTER_ADMIN")))
        assertTrue(entry.can(user("ADMIN")))
        assertFalse(entry.can(user("PROFESSOR")))
        assertFalse(entry.can(user("DESIGNER")))
    }

    @Test
    fun `an admin sees it only while admin view is on`() {
        assertTrue(isNavItemVisible(entry, user("ADMIN"), adminMode = true))
        assertFalse(isNavItemVisible(entry, user("ADMIN"), adminMode = false))
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
        assertNull(navBadge(entry, 0))
        assertNull(navBadge(entry, -1))
        assertNotNull(navBadge(entry, 1))
    }

    @Test
    fun `the label is the one an admin is looking for, and is not the designer roster's`() {
        val designerRoster = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGNER_ROSTER }
        assertEquals("Who may sign in", entry.label)
        assertEquals("Designer roster", designerRoster.label)
    }
}
