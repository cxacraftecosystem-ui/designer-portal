package com.designprototype.workshop

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FIELD_NAV_ITEMS
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dashboard's Design workshop card, checked where it can be checked without a handset.
 *
 * WHY THIS FILE EXISTS. The card is the app's central object arriving on the app's first screen, and
 * the two things about it that can silently go wrong are both invisible to a reader:
 *
 *  1. It could be gated on the wrong predicate. Every OTHER card on that grid is filtered by
 *     `canCreate(mode)`, a rank ladder, and copying that habit — or copying the web tile's old
 *     `visible: creator` — puts the card in front of a PROFESSOR and a RESEARCHER, both of whom the
 *     API refuses. That failure has already shipped once on the menu row beside it (AppNavigation.kt
 *     :449): the entry rendered, Start worked, and a fortnight of stages and photographs was
 *     stranded on the phone because the draft store is local and every sync was refused for ever.
 *     A rank ladder and the set {DESIGNER, ADMIN, MASTER_ADMIN} agree on six roles out of seven, so
 *     walking every role is the only verification this rule admits.
 *  2. The card and the menu row could drift apart. They open the same screen, so if either offers
 *     what the other hides, one of the two is lying about what this account may do.
 *  3. A SECOND arrival could start passing `startCreating = true`. The card's primary is the only
 *     route onto the list that is allowed to open the create dialog; see the last test here for what
 *     a second one costs and why the source is the only place it can be checked.
 */
class DesignWorkshopCardTest {

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

    @Test
    fun `the card is offered to exactly the roles the server lets run a workshop`() {
        val offered = everyRole.filter { DesignWorkshopCard.visibleTo(user(it)) }.toSet()
        assertEquals(
            "the Design workshop card must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf("DESIGNER", "ADMIN", "MASTER_ADMIN"),
            offered
        )
    }

    @Test
    fun `the card is not gated on record creation, which is what the web tile got wrong`() {
        // `visible: creator` on the web dashboard tile is `canCreateRecords`, which a RESEARCHER and
        // a PROFESSOR both pass — so both were offered a card that lands on "Designer access
        // required". Transcribing that predicate into Kotlin would have rebuilt the stranded-workshop
        // trap from the screen a designer opens most.
        for (role in listOf("RESEARCHER", "PROFESSOR")) {
            val account = user(role)
            assertTrue("$role can create records", FieldPermissions.canCreateRecords(account))
            assertFalse("$role must not be offered the card", DesignWorkshopCard.visibleTo(account))
        }
    }

    @Test
    fun `the card and the menu row read the same predicate for every role`() {
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOPS }
        val disagreements = everyRole.filter { role ->
            row.can(user(role)) != DesignWorkshopCard.visibleTo(user(role))
        }
        assertEquals(
            "the dashboard card and the Design workshops menu row must never disagree",
            emptyList<String>(),
            disagreements
        )
    }

    @Test
    fun `the card is singular and the menu row is plural, on purpose`() {
        // Both strings are the web's own, and the difference between them is deliberate on both
        // clients: the card names the THING you are about to make ("Design workshop", the dashboard
        // tile in frontend/app/(protected)/dashboard/page.tsx), the menu names the LIST you are
        // about to open ("Design workshops", DynamicIslandNav's entry). Asserted together so that
        // "tidying" one into the other's number fails here rather than in a designer's hands.
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOPS }
        assertEquals("Design workshop", DesignWorkshopCard.LABEL)
        assertEquals("Design workshops", row.label)
        assertNotEquals(DesignWorkshopCard.LABEL, row.label)
    }

    @Test
    fun `the primary button says what the web tile says`() {
        // Not the generic "New" every record card uses: the tile's own `newLabel` on the web
        // dashboard. A designer moves between the phone and the laptop mid-workshop and the same
        // button has to carry the same word.
        assertEquals("New workshop", DesignWorkshopCard.PRIMARY_LABEL)
    }

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same helper, same reasoning, as `DwWorkshopSearchRegistryTest`.
     */
    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    /**
     * ONE ARRIVAL MAY OPEN THE CREATE DIALOG, and it is the dashboard card's primary.
     *
     * This is the half of the card that is not a predicate, and nothing in the type system holds it.
     * `Screen.DesignWorkshops` carries `startCreating` with a `false` default precisely so that every
     * other way onto this list builds it false; if a second call site ever passes true, the two
     * arrivals it would most likely be are the two that must never do it — the drawer row, which
     * would then mint an empty 22-stage record on every tap and leave a trail nobody can tell apart,
     * and the back step out of a workshop, which would hand a designer leaving stage 14 a create
     * dialog. Both are a fortnight of fieldwork wide.
     *
     * READ FROM THE SOURCE, because `Screen` is file-private to MainActivity.kt and no unit test can
     * construct one — and because what is being defended against is somebody ADDING a call site,
     * which source inspection catches and an instrumented test of the existing ones would not. Same
     * instrument, for the same reason, as
     * `backend/tests/test_design_workshop_gate.py::test_every_write_route_carries_the_gate`.
     */
    @Test
    fun `exactly one arrival opens the create dialog, and it is the dashboard card's`() {
        val source = repoFile(
            "src/main/java/com/designprototype/workshop/MainActivity.kt",
            "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
            "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        ).readText()

        val constructions = Regex("""Screen\.DesignWorkshops\(([^)]*)\)""")
            .findAll(source)
            .map { it.groupValues[1].trim() }
            .toList()

        // A rename that emptied this list would otherwise leave the assertion below passing on
        // nothing at all, which is the failure mode of every source-reading test.
        assertTrue(
            "MainActivity no longer constructs Screen.DesignWorkshops anywhere — follow the rename, " +
                "do not delete the rule",
            constructions.size >= 3
        )
        assertEquals(
            "exactly one route onto the design-workshop list may arrive with the create dialog up",
            listOf("startCreating = true"),
            constructions.filter { it.isNotEmpty() }
        )
        // The two arrivals the route's own KDoc names by hand, asserted verbatim so that a change to
        // either fails here rather than in a courtyard.
        assertTrue(
            "the Design workshops drawer row must build the route with startCreating false",
            "NavDestination.DESIGN_WORKSHOPS -> screen = Screen.DesignWorkshops()" in source
        )
        assertTrue(
            "backing out of a workshop must build the route with startCreating false",
            "is Screen.DesignWorkshopStages -> Screen.DesignWorkshops()" in source
        )
    }
}
