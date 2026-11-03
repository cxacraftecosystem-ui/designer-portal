package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPermissionsTest {

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

    @Test
    fun `a professor may not run design workshops`() {
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("PROFESSOR")))
    }

    @Test
    fun `design workshops are a set and not a threshold, for every role at once`() {
        val allowed = everyRole.filter { FieldPermissions.canRunDesignWorkshops(user(it)) }.toSet()
        assertEquals(
            "canRunDesignWorkshops must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf(
                "DESIGNER",
                "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
                "ADMIN", "MASTER_ADMIN"
            ),
            allowed
        )
    }

    @Test
    fun `the rule is genuinely non-monotonic`() {
        assertTrue(FieldPermissions.rank("PROFESSOR") > FieldPermissions.rank("DESIGNER"))
        assertTrue(FieldPermissions.canRunDesignWorkshops(user("DESIGNER")))
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("PROFESSOR")))
        assertTrue(FieldPermissions.canRunDesignWorkshops(user("ADMIN")))
    }

    @Test
    fun `a researcher may create records but may not run a design workshop`() {
        val researcher = user("RESEARCHER")
        assertTrue(FieldPermissions.canCreateRecords(researcher))
        assertFalse(FieldPermissions.canRunDesignWorkshops(researcher))
    }

    @Test
    fun `every design-workshop nav entry is gated on the design-workshop predicate`() {
        val designWorkshopDestinations = setOf(
            NavDestination.DESIGN_WORKSHOPS,
            NavDestination.CUSTOM_QUESTIONNAIRES,
            NavDestination.DESIGNER_PROFILE,
        )
        val offending = FIELD_NAV_ITEMS
            .filter { it.destination in designWorkshopDestinations }
            .filter { it.can(user("PROFESSOR")) || it.can(user("RESEARCHER")) }
            .map { it.label }

        assertEquals("these entries are offered to an account the API refuses: $offending", emptyList<String>(), offending)
    }

    @Test
    fun `a designer is offered every design-workshop destination`() {
        val designer = user("DESIGNER")
        val offered = FIELD_NAV_ITEMS
            .filter {
                it.destination == NavDestination.DESIGN_WORKSHOPS ||
                    it.destination == NavDestination.CUSTOM_QUESTIONNAIRES ||
                    it.destination == NavDestination.DESIGNER_PROFILE
            }
            .filter { it.can(designer) }
        assertEquals(3, offered.size)
    }

    @Test
    fun `the ladder itself still matches the server for the monotonic rules`() {
        assertEquals(
            listOf(10, 20, 30, 35, 37, 40, 42, 45, 48, 50, 60),
            everyRole.map { FieldPermissions.rank(it) }
        )
        assertEquals(0, FieldPermissions.rank("SOMETHING_NEW"))

        assertFalse(FieldPermissions.canCreateRecords(user("FIELD_CONTRIBUTOR")))
        assertTrue(FieldPermissions.canCreateRecords(user("PROFESSOR")))
        assertFalse(FieldPermissions.isAdmin(user("PROFESSOR")))
        assertTrue(FieldPermissions.isAdmin(user("ADMIN")))
        assertFalse(FieldPermissions.canManageDesignerRoster(user("DESIGNER")))
        assertTrue(FieldPermissions.canManageDesignerRoster(user("MASTER_ADMIN")))
    }

    @Test
    fun `the content admins are exactly the three admin tiers, by set and not by rank`() {
        val contentAdmins = setOf("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")
        assertEquals(contentAdmins, FieldPermissions.CONTENT_ADMIN_ROLES)
        for (role in everyRole) {
            assertEquals(role, role in contentAdmins, FieldPermissions.isContentAdmin(user(role)))
        }
        assertFalse(FieldPermissions.isContentAdmin(null))
        assertFalse(FieldPermissions.isAdmin(user("MINISTRY_ADMIN")))
    }
}
