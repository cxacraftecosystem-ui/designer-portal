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

/**
 * THE TWO DOORS OF THE INSPECTION FEATURE, WALKED TIER BY TIER — AND THEY ARE DISJOINT.
 *
 * ── WHY THIS FILE EXISTS, AND WHY READING THE CODE IS NOT ENOUGH ─────────────────────────────────
 *
 * Every other capability predicate on this handset has the property that a MASTER_ADMIN passes
 * whatever a lesser tier passes: they are rank floors, or — for `canRunDesignWorkshops` — a set that
 * still contains both admin tiers. **`canInspectDesignWorkshops` breaks that outright.** The server's
 * `INSPECTION_ROLES` is `frozenset({"INSPECTOR"})` and `assert_inspection_surface` answers 403 to an
 * ADMIN and to a MASTER ADMIN BY NAME, with an argument in its own docstring: an admin scoped by
 * their own inspection rows sees an empty page and reads it as a broken feature, and an admin scoped
 * by "everything" turns this prefix into a second full read of the archive.
 *
 * So the ONE predicate a reader reaches for by habit — `rank(role) >= RANK_INSPECTOR` — is wrong for
 * THREE of the eight tiers, and every one of those three is an account that would be OFFERED the menu
 * row and then landed on a 403. It is not a hypothesis that somebody reaches for it: the brief this
 * work was commissioned from said "for INSPECTOR and above" in exactly those words, and the web lane
 * had to correct it against the source before shipping.
 *
 * The mistake is invisible on a phone. The drawer would carry an extra row, the row would open a
 * screen, the screen would ask, and the server would refuse — with nothing anywhere saying that the
 * refusal was the design rather than a fault.
 *
 * ── THE ASSERTION IS EQUALITY WITH A SET, NEVER "AT LEAST" ───────────────────────────────────────
 *
 * Both directions matter and they cost different things. Too wide, and an admin is offered a
 * capability the API refuses. Too narrow, and the tier the whole feature was built for cannot find
 * it — which is the state this handset was in until this wave, when `grep -rn
 * "design-workshop-inspections" android/app/src` answered nothing at all.
 *
 * ── AND THE SECOND DOOR IS ASSERTED TO BE THE FIRST ONE'S COMPLEMENT ─────────────────────────────
 *
 * [mayAdministerInspections] is `require_admin`. Nothing in this repository previously had a pair of
 * gates over one feature where passing one PROVES you fail the other, so the fact is written down
 * here rather than left for a reader to notice: an account that can appoint an inspector can never
 * read an inspection, and vice versa.
 */
class InspectionGateTest {

    private fun user(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    /** Every role the server's `ROLE_RANK` knows, so a new tier cannot be added without a decision. */
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

    // ── The read door ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `exactly one tier may read an inspection, and it is not the top of the ladder`() {
        val admitted = everyRole.filter { canInspectDesignWorkshops(it) }
        assertEquals(
            "INSPECTION_ROLES is frozenset({\"INSPECTOR\"}) and assert_inspection_surface 403s " +
                "everybody else by name — including both admin tiers",
            listOf("INSPECTOR"),
            admitted
        )
    }

    @Test
    fun `a master admin is refused the read surface, which no other predicate in this app does`() {
        // WRITTEN OUT SEPARATELY FROM THE EQUALITY ABOVE, because it is the single fact a reader is
        // most likely to disbelieve and "re-derive" back to a rank comparison. If this line ever
        // needs deleting, the server's `assert_inspection_surface` is what has to change first.
        assertFalse(canInspectDesignWorkshops("MASTER_ADMIN"))
        assertFalse(canInspectDesignWorkshops("ADMIN"))
        // And the tier immediately ABOVE the inspector on the ladder is refused too, so nobody can
        // read the set as "37 and up".
        assertFalse(canInspectDesignWorkshops("PROFESSOR"))
        assertTrue(canInspectDesignWorkshops("INSPECTOR"))
    }

    @Test
    fun `the ladder gives the wrong answer for three of the eight tiers`() {
        // THE COUNTER-ASSERTION, so the equality above is not merely true but non-obvious. This is
        // the predicate somebody will write instead; the cells it disagrees on are named here so
        // that a reader who deletes the set can see exactly what they are buying.
        val byRank = everyRole.filter { FieldPermissions.rank(it) >= FieldPermissions.RANK_INSPECTOR }
        val bySet = everyRole.filter { canInspectDesignWorkshops(it) }
        assertEquals(
            "a rank floor at 37 would admit the professor and both admin tiers",
            listOf("INSPECTOR", "PROFESSOR", "ADMIN", "MASTER_ADMIN"),
            byRank
        )
        assertEquals(listOf("PROFESSOR", "ADMIN", "MASTER_ADMIN"), byRank - bySet.toSet())
    }

    @Test
    fun `an unknown role is refused, so a server one tier ahead fails closed`() {
        assertFalse(canInspectDesignWorkshops("SUPERUSER"))
        assertFalse(canInspectDesignWorkshops(""))
        assertFalse(canInspectDesignWorkshops(null))
    }

    // ── The appointment door ─────────────────────────────────────────────────────────────────────

    @Test
    fun `appointing an inspector is admin only`() {
        val admitted = everyRole.filter { mayAdministerInspections(user(it)) }
        assertEquals(
            "both administration routes are Depends(require_admin) — deps.is_admin, and NOT the " +
                "workshop's creator, who is refused by name so that the inspected cannot choose the " +
                "inspector",
            listOf("ADMIN", "MASTER_ADMIN"),
            admitted
        )
        assertFalse("a signed-out account administers nothing", mayAdministerInspections(null))
    }

    @Test
    fun `the two doors are disjoint - passing one proves you fail the other`() {
        // The fact this whole feature turns on, and the reason the appointment screen has its OWN
        // predicate rather than reusing the read one. Nothing else in this app has this shape.
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
        // `INSPECTION_ROLES` and `deps.DESIGN_WORKSHOP_ROLES` are asserted DISJOINT at import time on
        // the server, and this is the handset's copy of that invariant. If they ever overlap, one
        // account becomes eligible to hold both a viewer row — which carries STAGE WRITES, because
        // `load_workshop_or_404(for_edit=True)` performs no role check — and an inspection row, which
        // is read-only, on the same workshop.
        assertFalse(FieldPermissions.canRunDesignWorkshops(user("INSPECTOR")))
        everyRole.forEach { role ->
            assertFalse(
                "$role would hold both a write grant and a read-only inspection on one workshop",
                canInspectDesignWorkshops(role) && FieldPermissions.canRunDesignWorkshops(user(role))
            )
        }
    }

    // ── The menu row ─────────────────────────────────────────────────────────────────────────────

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
        // The web's label, verbatim. A researcher moves between the two apps mid-workshop.
        //
        // AND THE SAME STRING THE LIST PAGE HEADS ITSELF WITH. The nav table holds a literal and the
        // screen holds a constant, so nothing in the compiler relates them; this line is what does.
        // A menu row whose label differs from the heading it opens sends a reader looking for a
        // second feature.
        assertEquals("Workshops to inspect", DW_INSPECTION_LIST_TITLE)
        assertEquals(DW_INSPECTION_LIST_TITLE, row.label)
        assertEquals(NavGroup.BROWSE, row.group)
        assertFalse(
            "adminSurface hides a row from an admin browsing as an ordinary user, and there is no " +
                "admin here to hide it from — the predicate has already refused them",
            row.adminSurface
        )
        // THE ROW AND THE SCREENS READ ONE PREDICATE. A row gated on anything else would offer the
        // menu entry to an account the screens then refuse in their own words.
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
        // BOTH TOGGLE POSITIONS, because `adminSurface` is the one thing that can hide a row from an
        // account whose predicate passed — and an admin flipping "browse as an ordinary user" must
        // not be the thing that makes this row appear or disappear, in either direction.
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
        // THE WHOLE MENU FOR THE TIER, asserted as a set rather than one row at a time. An inspector
        // is NOT in `DESIGN_WORKSHOP_ROLES`, so every row gated on `canRunDesignWorkshops` — Design
        // workshops, Design review, Sketches & prototypes, Questionnaires, My designer profile — must
        // be absent, and offering any of them would be a menu entry in front of a 403 on a tier that
        // has exactly one surface.
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

    // ── readOnly, which is the other half of "never offer a control the API would 404" ───────────

    @Test
    fun `an absent readOnly means read-only, because the quiet side here is the dangerous one`() {
        // A deployment that predates the key sends no flag. A `== true` test would then draw a Save
        // button on a prefix with no write route at all — the exact bug the boolean was put on the
        // wire to prevent. This is the OPPOSITE of how `truncated` is treated one type over, where
        // an unknown flag must stay quiet, and the asymmetry is deliberate.
        assertTrue("absent must fail closed", dwInspectionIsReadOnly(null))
        assertTrue(dwInspectionIsReadOnly(true))
        // An explicit false IS honoured, because that is the value the designer's read will carry the
        // day one screen serves both. Delete this and the shared-screen change becomes unfindable.
        assertFalse(dwInspectionIsReadOnly(false))
    }

    @Test
    fun `every design-workshop destination is refused on a read, and admitted when it is not one`() {
        // WALKED IN FULL, so that a tenth destination added to the designer's workshop is refused
        // the moment it is listed rather than shipping as a control that 404s.
        DwInspectionDestination.entries.forEach {
            assertFalse("$it was offered on a read", dwInspectionMayOpen(it, true))
            assertFalse("$it was offered on a payload with no flag", dwInspectionMayOpen(it, null))
        }
        // THE COUNTERPART, so the assertion above is not vacuously true of a function that returns
        // false unconditionally.
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
