package com.designprototype.workshop

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FIELD_NAV_ITEMS
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.NavDestination
import com.designprototype.workshop.ui.SearchRecordTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchRecordRoutingTest {

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
    fun `the route is offered to exactly the roles the server lets open a workshop`() {
        val offered = everyRole.filter { SearchDesignWorkshopRoute.offeredTo(user(it)) }.toSet()
        assertEquals(
            "a tap onto a design workshop must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf(
                "DESIGNER",
                "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
                "ADMIN", "MASTER_ADMIN"
            ),
            offered
        )
    }

    @Test
    fun `the search route, the menu row and the dashboard card never disagree`() {
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.DESIGN_WORKSHOPS }
        val disagreements = everyRole.filter { role ->
            val account = user(role)
            val fromSearch = SearchDesignWorkshopRoute.offeredTo(account)
            fromSearch != row.can(account) || fromSearch != DesignWorkshopCard.visibleTo(account)
        }
        assertEquals(
            "the search route, the Design workshops menu row and the dashboard card must agree",
            emptyList<String>(),
            disagreements
        )
    }

    @Test
    fun `a professor may search design workshops and may not open one, which is the whole gate`() {
        val professor = user("PROFESSOR")
        assertTrue(
            "a professor must keep the search chip",
            FieldPermissions.canViewDesignWorkshopData(professor)
        )
        assertFalse(
            "a professor must not be handed a tappable design-workshop row",
            SearchDesignWorkshopRoute.offeredTo(professor)
        )
        val designer = user("DESIGNER")
        assertTrue(SearchDesignWorkshopRoute.offeredTo(designer))
        assertFalse(FieldPermissions.canViewDesignWorkshopData(designer))
    }

    @Test
    fun `an unknown record type is refused by name rather than opened as something else`() {
        val line = unroutableRecordLine("gizmo")
        assertTrue("the refusal must say what fixes it: $line", "Update the app" in line)
        assertTrue("and must offer the way that does work: $line", "web portal" in line)
    }

    @Test
    fun `the design-workshop refusal names no menu row, because its reader has none`() {
        val line = unroutableRecordLine(SearchRecordTypes.DESIGN_WORKSHOP)
        assertEquals(DESIGN_WORKSHOP_NO_ROUTE_LINE, line)
        assertFalse("the refusal must not name the Design workshops menu row: $line", "menu" in line)
        assertFalse("nor send them to the web portal, which refuses them too: $line", "portal" in line)
        assertNotEquals(
            "a design workshop is a permission, not an unknown type — the two must not share wording",
            unroutableRecordLine("gizmo"),
            line
        )
    }

    private fun mainActivitySource(): String = repoFile(
        "src/main/java/com/designprototype/workshop/MainActivity.kt",
        "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
    ).readText().replace("\r\n", "\n")

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

    private fun searchRecordEntryModeBody(): String {
        val source = mainActivitySource()
        val declaration = "private fun searchRecordEntryMode(recordType: String): EntryMode? = when (recordType) {"
        val start = source.indexOf(declaration)
        assertTrue(
            "searchRecordEntryMode must still be declared nullable — if it was renamed, follow the " +
                "rename; if its return type went back to a non-null EntryMode, the trap is back",
            start >= 0
        )
        val end = source.indexOf("\n}", start)
        assertTrue("the mapper's closing brace was not found", end > start)
        return source.substring(start, end + 2)
    }

    @Test
    fun `no arm of the search mapper falls back to a record type`() {
        val body = searchRecordEntryModeBody()
        assertFalse(
            "the mapper's else must not answer with a record type:\n$body",
            Regex("""else\s*->\s*EntryMode\.""").containsMatchIn(body)
        )
        assertTrue("the mapper's else must be null:\n$body", "else -> null" in body)
    }

    @Test
    fun `the design workshop is refused by an arm of its own, not by falling through`() {
        val body = searchRecordEntryModeBody()
        assertTrue(
            "designWorkshop must be named in the mapper:\n$body",
            "SearchRecordTypes.DESIGN_WORKSHOP -> null" in body
        )
    }

    @Test
    fun `the three surfaces share one arm rather than three copies of it`() {
        val source = mainActivitySource()
        assertFalse(
            "no surface may build Screen.Edit from the mapper itself — route through openSearchRecord",
            "Screen.Edit(searchRecordEntryMode(" in source
        )
        val shared = Regex("""on(OpenRecord|Open) = openSearchRecord""").findAll(source).count()
        assertEquals(
            "the search screen, the map and the code scanner must all pass the one shared arm",
            3,
            shared
        )
    }

    @Test
    fun `the search screen is passed the design-workshop route, and only that route`() {
        val source = mainActivitySource()
        val passes = Regex("""onOpenDesignWorkshop = (\w+)""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(
            "SearchScreen.onOpenDesignWorkshop must be passed openDesignWorkshopFromSearch, once",
            listOf("openDesignWorkshopFromSearch"),
            passes
        )
    }

    @Test
    fun `the route onto a workshop is built in exactly one place, behind the gate`() {
        val source = mainActivitySource()
        val definitions = Regex("""val openDesignWorkshopFromSearch""").findAll(source).count()
        assertEquals("openDesignWorkshopFromSearch must be defined once", 1, definitions)
        assertTrue(
            "the route must be null for an account outside SearchDesignWorkshopRoute",
            "if (SearchDesignWorkshopRoute.offeredTo(user)) {" in source
        )
    }
}
