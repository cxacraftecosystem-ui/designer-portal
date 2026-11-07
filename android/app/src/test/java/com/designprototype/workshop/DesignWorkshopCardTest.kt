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
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `the card is offered to exactly the roles the server lets run a workshop`() {
        val offered = everyRole.filter { DesignWorkshopCard.visibleTo(user(it)) }.toSet()
        assertEquals(
            "the Design workshop card must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf(
                "DESIGNER",
                "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
                "ADMIN", "MASTER_ADMIN"
            ),
            offered
        )
    }

    @Test
    fun `the card is not gated on record creation, which is what the web tile got wrong`() {
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
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOPS }
        assertEquals("Design workshop", DesignWorkshopCard.LABEL)
        assertEquals("Design workshops", row.label)
        assertNotEquals(DesignWorkshopCard.LABEL, row.label)
    }

    @Test
    fun `the primary button says what the web tile says`() {
        assertEquals("New workshop", DesignWorkshopCard.PRIMARY_LABEL)
    }

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

        assertTrue(
            "MainActivity no longer constructs Screen.DesignWorkshops anywhere — follow the rename, " +
                "do not delete the rule",
            constructions.size >= 3
        )
        assertEquals(
            "exactly one route onto the design-workshop list may arrive with the create dialog up",
            listOf("startCreating = true"),
            constructions.filter { "startCreating" in it }
        )
        assertTrue(
            "the Design workshops drawer row must build the route with startCreating false",
            "NavDestination.DESIGN_WORKSHOPS -> screen = Screen.DesignWorkshops()" in source
        )
        assertTrue(
            "backing out of a workshop must build the route with startCreating false",
            "is Screen.DesignWorkshopStages -> s.from ?: Screen.DesignWorkshops()" in source
        )
    }
}
