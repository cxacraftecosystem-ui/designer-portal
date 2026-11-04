package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorateTiersTest {

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

    private val directorate = listOf("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN")

    @Test
    fun `the three directorate ranks are 42, 45 and 48, above a professor and below an admin`() {
        assertEquals(42, FieldPermissions.rank("ASSISTANT_DIRECTOR"))
        assertEquals(45, FieldPermissions.rank("REGIONAL_DIRECTOR"))
        assertEquals(48, FieldPermissions.rank("MINISTRY_ADMIN"))

        assertTrue(FieldPermissions.rank("PROFESSOR") < FieldPermissions.rank("ASSISTANT_DIRECTOR"))
        assertTrue(FieldPermissions.rank("ASSISTANT_DIRECTOR") < FieldPermissions.rank("REGIONAL_DIRECTOR"))
        assertTrue(FieldPermissions.rank("REGIONAL_DIRECTOR") < FieldPermissions.rank("MINISTRY_ADMIN"))
        assertTrue(FieldPermissions.rank("MINISTRY_ADMIN") < FieldPermissions.RANK_ADMIN)
    }

    @Test
    fun `the three labels are the job titles and nothing else`() {
        assertEquals("Assistant Director", FieldPermissions.label("ASSISTANT_DIRECTOR"))
        assertEquals("Regional Director", FieldPermissions.label("REGIONAL_DIRECTOR"))
        assertEquals("Ministry Admin", FieldPermissions.label("MINISTRY_ADMIN"))
        everyRole.forEach { role ->
            assertFalse("$role renders as a raw token", FieldPermissions.label(role) == role)
        }
    }

    @Test
    fun `a ministry admin is not an admin on this handset, and neither are the other two`() {
        directorate.forEach { role ->
            assertFalse(
                "$role passes isAdmin. The token says admin on one of these and the tier is not " +
                    "one: the server's `is_admin` is set membership on MASTER_ADMIN and ADMIN, and " +
                    "this client's floor at 50 must keep agreeing with it.",
                FieldPermissions.isAdmin(user(role))
            )
            assertFalse(FieldPermissions.isMasterAdmin(user(role)))
            assertFalse("the roster screens are admin-only", FieldPermissions.canManageAccessRoster(user(role)))
            assertFalse("the designer roster is admin-only", FieldPermissions.canManageDesignerRoster(user(role)))
        }
    }

    @Test
    fun `all three clear every professor floor on this client, which is inherited and intended`() {
        directorate.forEach { role ->
            assertTrue("$role manages crafts", FieldPermissions.canManageCrafts(user(role)))
            assertTrue("$role manages workshops", FieldPermissions.canManageWorkshops(user(role)))
            assertTrue("$role opens the user table", FieldPermissions.canManageUsers(user(role)))
            assertTrue("$role downloads the dataset", FieldPermissions.canDownloadDataset(user(role)))
            assertTrue("$role opens the review queue", FieldPermissions.canReview(user(role)))
            assertTrue("$role creates records", FieldPermissions.canCreateRecords(user(role)))
            assertTrue(FieldPermissions.rank(role) >= FieldPermissions.RANK_PROFESSOR)
        }
    }

    @Test
    fun `the three tiers run a workshop and still do not create or inspect one, because each is its own set`() {
        directorate.forEach { role ->
            assertTrue("$role runs a workshop", FieldPermissions.canRunDesignWorkshops(user(role)))
            assertFalse("$role creates a workshop", FieldPermissions.canCreateDesignWorkshops(user(role)))
            assertFalse("$role inspects a workshop", FieldPermissions.canInspectDesignWorkshops(user(role)))
        }

        for (role in listOf("PROFESSOR", "INSPECTOR")) {
            assertFalse("$role runs a workshop", FieldPermissions.canRunDesignWorkshops(user(role)))
        }
    }

    @Test
    fun `reading design-workshop data on screen is the one set the three tiers were added to`() {
        directorate.forEach { role ->
            assertTrue("$role reads design-workshop data", FieldPermissions.canViewDesignWorkshopData(user(role)))
        }
        assertTrue(FieldPermissions.canViewDesignWorkshopData(user("PROFESSOR")))
        assertFalse(
            "an inspector holds a grant on ONE workshop; this predicate opens every workshop there is",
            FieldPermissions.canViewDesignWorkshopData(user("INSPECTOR"))
        )
        assertFalse(FieldPermissions.canViewDesignWorkshopData(user("DESIGNER")))
    }
}
