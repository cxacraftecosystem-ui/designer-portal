package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's copy of the three DIRECTORATE tiers, added 2026-09-13 at 42/45/48.
 *
 * WHY THIS FILE EXISTS AT ALL, WHEN [FieldPermissionsTest] ALREADY WALKS EVERY ROLE. That suite
 * asserts the rules it was written for over whatever [everyRole] happens to contain, so adding three
 * tokens to its tuple widens its coverage and asserts nothing NEW about the three tiers. What needed
 * writing down is the pair of facts a reader of `FieldPermissions` will get wrong:
 *
 *  1. **A rank above 40 clears every `>= RANK_PROFESSOR` predicate in this client at once** —
 *     [FieldPermissions.canManageCrafts], [FieldPermissions.canManageWorkshops],
 *     [FieldPermissions.canManageUsers], [FieldPermissions.canDownloadDataset] and
 *     `MainActivity`'s `canSetRecordStatus`. Nothing in Kotlin names a directorate tier; all three
 *     acquired those the moment the numbers existed. That is intended and it is asserted here so it
 *     cannot be re-acquired by accident later.
 *  2. **`MINISTRY_ADMIN` is not an admin.** [FieldPermissions.isAdmin] is a floor at
 *     [FieldPermissions.RANK_ADMIN] (50) on this client and set membership on {MASTER_ADMIN, ADMIN}
 *     on the server; 48 fails both. The token's English reading and its meaning in the code point in
 *     opposite directions, which is exactly the case a test should own rather than a comment.
 *
 * THE CLIENT AGREES WITH THE SERVER BY ARITHMETIC HERE AND BY CONSTRUCTION THERE, which is the one
 * thing to keep in view: a tier inserted ABOVE 50 would make [FieldPermissions.isAdmin] and
 * `deps.is_admin` disagree, and this drawer would offer an admin-only destination to somebody the
 * API refuses. `backend/tests/test_directorate_tiers.py` is the server-side twin of this file.
 *
 * ON `MainActivity`-PARITY: `MainActivity.roleRank` and `MainActivity.canSetRecordStatus` are
 * PRIVATE top-level functions in that file and cannot be called from a test. The properties they
 * carry are asserted here through [FieldPermissions], which mirrors the same two maps; the parity
 * between the two Kotlin copies is what `backend/tests/test_role_ladder_parity.py` rows 13-16 hold.
 */
class DirectorateTiersTest {

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

        // A tier missing from `RANKS` falls to `?: 0` — BELOW a crowdsource volunteer — and the
        // drawer then hides every destination, so the phone "looks signed in and has nothing in it".
        // This is the assertion that catches a Kotlin map that was not updated with the server.
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
        // A role missing from LABELS renders as its raw UPPER_SNAKE token beside somebody's name.
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
            // `MainActivity.canSetRecordStatus` is `roleRank(role) >= RANK_PROFESSOR` and private to
            // that file; the same floor, spelled through this object, is what it agrees with.
            assertTrue(FieldPermissions.rank(role) >= FieldPermissions.RANK_PROFESSOR)
        }
    }

    @Test
    fun `no rank above a professor buys any design-workshop authority, because every one is a set`() {
        directorate.forEach { role ->
            assertFalse("$role runs a workshop", FieldPermissions.canRunDesignWorkshops(user(role)))
            assertFalse("$role creates a workshop", FieldPermissions.canCreateDesignWorkshops(user(role)))
            assertFalse("$role inspects a workshop", FieldPermissions.canInspectDesignWorkshops(user(role)))
        }
    }

    @Test
    fun `reading design-workshop data on screen is the one set the three tiers were added to`() {
        // The 2026-09-13 ruling, asserted as a pair with the refusal above so neither half can move
        // alone: all three READ stage data (`DESIGN_WORKSHOP_DATA_VIEW_ROLES`) and none of them RUNS
        // a workshop. A professor has held exactly this shape since 2026-08-30; the ruling widened
        // the set by three tiers rather than redrawing it.
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
