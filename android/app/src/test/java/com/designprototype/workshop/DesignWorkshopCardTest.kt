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
 */
class DesignWorkshopCardTest {

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
        // tile at frontend/app/(protected)/dashboard/page.tsx:175), the menu names the LIST you are
        // about to open ("Design workshops", DynamicIslandNav's entry). Asserted together so that
        // "tidying" one into the other's number fails here rather than in a designer's hands.
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOPS }
        assertEquals("Design workshop", DesignWorkshopCard.LABEL)
        assertEquals("Design workshops", row.label)
        assertNotEquals(DesignWorkshopCard.LABEL, row.label)
    }

    @Test
    fun `the primary button says what the web tile says`() {
        // Not the generic "New" every record card uses: the tile's own `newLabel` at
        // dashboard/page.tsx:180. A designer moves between the phone and the laptop mid-workshop and
        // the same button has to carry the same word.
        assertEquals("New workshop", DesignWorkshopCard.PRIMARY_LABEL)
    }
}
