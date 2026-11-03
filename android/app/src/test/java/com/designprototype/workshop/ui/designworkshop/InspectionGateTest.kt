package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwInspectionDestination
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.dwInspectionIsReadOnly
import com.designprototype.workshop.data.dwInspectionMayOpen
import com.designprototype.workshop.ui.FIELD_NAV_ITEMS
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.NavDestination
import com.designprototype.workshop.ui.NavGroup
import com.designprototype.workshop.ui.isNavItemVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionGateTest {

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
    fun `exactly one tier may read an inspection, and it is not the top of the ladder`() {
        val admitted = everyRole.filter { canInspectDesignWorkshops(it) }
        assertEquals(
            "the handset's read door is the Inspector / Reviewer tier — the server's INSPECTION_ROLES. " +
                "The server also admits the three administering tiers by appointment since 2026-10-09, " +
                "and this handset leaves that to the web (D5)",
            listOf("INSPECTOR"),
            admitted
        )
    }

    @Test
    fun `a master admin is refused the read surface, which no other predicate in this app does`() {
        assertFalse(canInspectDesignWorkshops("MASTER_ADMIN"))
        assertFalse(canInspectDesignWorkshops("ADMIN"))
        assertFalse(canInspectDesignWorkshops("PROFESSOR"))
        assertTrue(canInspectDesignWorkshops("INSPECTOR"))
    }

    @Test
    fun `the ladder gives the wrong answer for six of the eleven tiers`() {
        val byRank = everyRole.filter { FieldPermissions.rank(it) >= FieldPermissions.RANK_INSPECTOR }
        val bySet = everyRole.filter { canInspectDesignWorkshops(it) }
        assertEquals(
            "a rank floor at 37 would admit the professor, all three directorate tiers and both "
                + "admin tiers",
            listOf(
                "INSPECTOR", "PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR",
                "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"
            ),
            byRank
        )
        assertEquals(
            listOf(
                "PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN",
                "ADMIN", "MASTER_ADMIN"
            ),
            byRank - bySet.toSet()
        )
    }

    @Test
    fun `an unknown role is refused, so a server one tier ahead fails closed`() {
        assertFalse(canInspectDesignWorkshops("SUPERUSER"))
        assertFalse(canInspectDesignWorkshops(""))
        assertFalse(canInspectDesignWorkshops(null))
    }

    @Test
    fun `appointing an inspector is admin only`() {
        val admitted = everyRole.filter { mayAdministerInspections(user(it)) }
        assertEquals(
            "on this handset the appointment screen is deps.is_admin — the server's " +
                "require_workshop_assigner also admits a Ministry Admin, whose surfaces are web-only — " +
                "and never a designer, so that the inspected cannot choose the inspector",
            listOf("ADMIN", "MASTER_ADMIN"),
            admitted
        )
        assertFalse("a signed-out account administers nothing", mayAdministerInspections(null))
    }

    @Test
    fun `the two doors are disjoint - passing one proves you fail the other`() {
        everyRole.forEach { role ->
            assertFalse(
                "$role passes both doors, which no account may",
                canInspectDesignWorkshops(role) && mayAdministerInspections(user(role))
            )
        }
        assertTrue(everyRole.any { canInspectDesignWorkshops(it) })
        assertTrue(everyRole.any { mayAdministerInspections(user(it)) })
    }

    @Test
    fun `an inspector may not run a design workshop, so widening either set is visible here`() {
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("INSPECTOR")))
        everyRole.forEach { role ->
            assertFalse(
                "$role would hold both a write grant and a read-only inspection on one workshop",
                canInspectDesignWorkshops(role) && FieldPermissions.canRunDesignWorkshops(user(role))
            )
        }
    }

    @Test
    fun `the nav row exists, is in Browse, and reads the same predicate as the screens`() {
        val row = FIELD_NAV_ITEMS.firstOrNull {
            it.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS
        }
        assertNotNull(
            "the whole feature was invisible on this handset until a NAV_ITEMS row existed — a " +
                "screen with no door is a feature the owner reports as not shipped",
            row
        )
        requireNotNull(row)
        assertEquals("Workshops to inspect", DW_INSPECTION_LIST_TITLE)
        assertEquals(DW_INSPECTION_LIST_TITLE, row.label)
        assertEquals(NavGroup.BROWSE, row.group)
        assertFalse(
            "adminSurface hides a row from an admin browsing as an ordinary user, and there is no " +
                "admin here to hide it from — the predicate has already refused them",
            row.adminSurface
        )
        everyRole.forEach { role ->
            assertEquals(
                "the nav row and canInspectDesignWorkshops disagree about $role",
                canInspectDesignWorkshops(role),
                row.can(user(role))
            )
        }
    }

    @Test
    fun `the menu offers the row to the inspector and to nobody else, admin view on or off`() {
        listOf(true, false).forEach { adminMode ->
            val offered = everyRole.filter { role ->
                FIELD_NAV_ITEMS.any {
                    it.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS &&
                        isNavItemVisible(it, user(role), adminMode)
                }
            }
            assertEquals("with adminMode=$adminMode", listOf("INSPECTOR"), offered)
        }
        assertFalse(
            "a signed-out account is offered nothing",
            FIELD_NAV_ITEMS.any {
                it.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS &&
                    isNavItemVisible(it, null, true)
            }
        )
    }

    @Test
    fun `an inspector is offered this row and no design-workshop row`() {
        val offered = FIELD_NAV_ITEMS
            .filter { isNavItemVisible(it, user("INSPECTOR"), false) }
            .map { it.destination }
        assertTrue(offered.contains(NavDestination.DESIGN_WORKSHOP_INSPECTIONS))
        listOf(
            NavDestination.DESIGN_WORKSHOPS,
            NavDestination.DESIGN_REVIEW,
            NavDestination.SKETCHES_AND_PROTOTYPES,
            NavDestination.CUSTOM_QUESTIONNAIRES,
            NavDestination.DESIGNER_PROFILE,
        ).forEach {
            assertFalse("an inspector was offered $it, which every route behind it 404s", offered.contains(it))
        }
    }

    @Test
    fun `an absent readOnly means read-only, because the quiet side here is the dangerous one`() {
        assertTrue("absent must fail closed", dwInspectionIsReadOnly(null))
        assertTrue(dwInspectionIsReadOnly(true))
        assertFalse(dwInspectionIsReadOnly(false))
    }

    @Test
    fun `every design-workshop destination is refused on a read, and admitted when it is not one`() {
        DwInspectionDestination.entries.forEach {
            assertFalse("$it was offered on a read", dwInspectionMayOpen(it, true))
            assertFalse("$it was offered on a payload with no flag", dwInspectionMayOpen(it, null))
        }
        DwInspectionDestination.entries.forEach {
            assertTrue("$it must be reachable once readOnly is explicitly false", dwInspectionMayOpen(it, false))
        }
        assertEquals(
            "the nine routes a workshop offers a designer, mirroring DESIGN_WORKSHOP_DESTINATIONS " +
                "in frontend/lib/designWorkshopInspections.ts",
            9,
            DwInspectionDestination.entries.size
        )
    }
}
