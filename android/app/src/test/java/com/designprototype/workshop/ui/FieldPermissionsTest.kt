package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's capability table, checked against what the API actually answers.
 *
 * WHY THIS FILE EXISTS. [FieldPermissions.canRunDesignWorkshops] is the ONE rule in `deps.py` that is
 * not a rank threshold — it is the set {DESIGNER, ADMIN, MASTER_ADMIN}, so a PROFESSOR is outside it
 * despite outranking a designer. Written on the phone as `rank(role) >= RANK_DESIGNER` it gave the
 * same answer for six of the seven roles, which is precisely why nobody caught it by reading: the
 * ladder and the set differ in exactly one cell of a seven-row table. A test that walks every role is
 * the only form of verification a non-monotonic rule admits.
 *
 * Every expectation below was reproduced against the running API before it was written down:
 *
 *   RESEARCHER  POST /api/design-workshops   -> 403 "Running a design workshop requires Designer
 *                                                    access or above."
 *   PROFESSOR   GET  /api/questionnaires     -> 403
 *   PROFESSOR   GET  /api/designers/me/profile -> 403 "A designer profile belongs to the people who
 *                                                      run design & prototype workshops …"
 *   PROFESSOR   PUT  /api/designers/me/profile -> 403
 *   DESIGNER    GET  /api/designers/me/profile -> 200
 */
class FieldPermissionsTest {

    private fun user(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `a professor may not run design workshops`() {
        // The whole defect. rank("PROFESSOR") = 40 >= RANK_DESIGNER = 35, so the threshold said yes
        // and the drawer rendered "Questionnaires" and "My designer profile" for an account the API
        // refuses on both — and DesignerProfileScreen enabled the EDIT form, so a professor could
        // type the display name, designation, institution and biography a report is signed with,
        // press save, and be 403'd.
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("PROFESSOR")))
    }

    @Test
    fun `design workshops are a set and not a threshold, for every role at once`() {
        val allowed = everyRole.filter { FieldPermissions.canRunDesignWorkshops(user(it)) }.toSet()
        assertEquals(
            "canRunDesignWorkshops must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf("DESIGNER", "ADMIN", "MASTER_ADMIN"),
            allowed
        )
    }

    @Test
    fun `the rule is genuinely non-monotonic`() {
        // Stated as its own assertion so that "fixing" this back into a threshold cannot pass. A rank
        // ladder cannot express it: PROFESSOR sits between DESIGNER and ADMIN and is excluded while
        // both neighbours are included.
        assertTrue(FieldPermissions.rank("PROFESSOR") > FieldPermissions.rank("DESIGNER"))
        assertTrue(FieldPermissions.canRunDesignWorkshops(user("DESIGNER")))
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("PROFESSOR")))
        assertTrue(FieldPermissions.canRunDesignWorkshops(user("ADMIN")))
    }

    @Test
    fun `a researcher may create records but may not run a design workshop`() {
        // The other half of the pair. The nav entry gated design workshops on `canCreateRecords`,
        // which a researcher passes — so they saw the entry, tapped Start, and captured 22 stages
        // that could never sync, because the draft is local and every push was refused.
        val researcher = user("RESEARCHER")
        assertTrue(FieldPermissions.canCreateRecords(researcher))
        assertFalse(FieldPermissions.canRunDesignWorkshops(researcher))
    }

    @Test
    fun `every design-workshop nav entry is gated on the design-workshop predicate`() {
        // The nav table's own contract: "When it returns false the entry is NOT RENDERED … so the
        // menu only ever offers what the API would actually allow." These three destinations are the
        // ones whose every route runs `require_designer` on the server.
        val designWorkshopDestinations = setOf(
            NavDestination.DESIGN_WORKSHOPS,
            NavDestination.CUSTOM_QUESTIONNAIRES,
        )
        val offending = FIELD_NAV_ITEMS
            .filter { it.destination in designWorkshopDestinations }
            .filter { it.can(user("PROFESSOR")) || it.can(user("RESEARCHER")) }
            .map { it.label }

        assertEquals("these entries are offered to an account the API refuses: $offending", emptyList<String>(), offending)
    }

    @Test
    fun `a designer is offered every design-workshop destination`() {
        // The failure in the other direction is just as real: a predicate stricter than the backend
        // hides a screen from the one tier the feature was built for.
        val designer = user("DESIGNER")
        val offered = FIELD_NAV_ITEMS
            .filter { it.destination == NavDestination.DESIGN_WORKSHOPS || it.destination == NavDestination.CUSTOM_QUESTIONNAIRES }
            .filter { it.can(designer) }
        assertEquals(2, offered.size)
    }

    @Test
    fun `the ladder itself still matches the server for the monotonic rules`() {
        // Guards the constants the rest of the table is built on. A role missing from RANKS scores 0,
        // which is below a crowdsource volunteer, and the menu then hides every destination from it.
        assertEquals(listOf(10, 20, 30, 35, 40, 50, 60), everyRole.map { FieldPermissions.rank(it) })
        assertEquals(0, FieldPermissions.rank("SOMETHING_NEW"))

        assertFalse(FieldPermissions.canCreateRecords(user("FIELD_CONTRIBUTOR")))
        assertTrue(FieldPermissions.canCreateRecords(user("PROFESSOR")))
        assertFalse(FieldPermissions.isAdmin(user("PROFESSOR")))
        assertTrue(FieldPermissions.isAdmin(user("ADMIN")))
        assertFalse(FieldPermissions.canManageDesignerRoster(user("DESIGNER")))
        assertTrue(FieldPermissions.canManageDesignerRoster(user("MASTER_ADMIN")))
    }
}
