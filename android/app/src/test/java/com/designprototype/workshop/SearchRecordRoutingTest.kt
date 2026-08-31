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

/**
 * WHERE A TAPPED SEARCH ROW, A MAP PIN AND A SCANNED CARD GO — and where they must never go.
 *
 * ── WHAT WENT WRONG, SO THAT THIS FILE IS NOT READ AS PARANOIA ─────────────────────────────────
 *
 * `MainActivity.searchRecordEntryMode` ended `else -> EntryMode.ARTISAN`. Every one of the three
 * surfaces named above resolved a record type through it, so any string the mapper did not know
 * opened the ARTISAN EDITOR on that record's id — a form whose Save writes an artisan.
 *
 * The type that reached it was `designWorkshop`: twenty-two stages of somebody's fortnight in a
 * village. Two separate lanes found this independently and neither could fix it, because the mapper
 * lives in a file that was not theirs. The search lane shipped its whole design-workshop bucket
 * INERT rather than route through it; `RecordCodeLookup` answers a scanned `G` card with a join
 * request rather than offer an Open button that would have led here. Both workarounds are still in
 * place and still correct; what this lane changed is the thing they were working around.
 *
 * ── WHY THE ASSERTIONS SPLIT INTO TWO KINDS ────────────────────────────────────────────────────
 *
 * The predicate and the two refusal sentences are pure and are asserted directly. The mapper, the
 * shared arm and the wiring are all file-private to a 19,000-line composable that no unit test can
 * construct, so they are asserted by reading the source — the same instrument, for the same reason,
 * as [DesignWorkshopCardTest] and `test_design_workshop_gate.py::test_every_write_route_carries_the_gate`.
 * That instrument also catches the failure that matters most here, which is somebody ADDING a call
 * site; an instrumented test of the existing three would not.
 */
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
        "ADMIN",
        "MASTER_ADMIN",
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The route onto a design workshop, which is a predicate and can be walked role by role.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the route is offered to exactly the roles the server lets open a workshop`() {
        val offered = everyRole.filter { SearchDesignWorkshopRoute.offeredTo(user(it)) }.toSet()
        assertEquals(
            "a tap onto a design workshop must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf("DESIGNER", "ADMIN", "MASTER_ADMIN"),
            offered
        )
    }

    @Test
    fun `the search route, the menu row and the dashboard card never disagree`() {
        // Three doors onto one screen. If any two disagree, one of them is lying to an account about
        // what it may do — and the one that lies by OFFERING costs a tap that ends on "Record not
        // found", which is precisely what the inert bucket was protecting against.
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
        // The case the gate exists for, named rather than left implicit in the set comparison above.
        // A professor is INSIDE the read set (the search chip is offered, the server returns the
        // bucket) and OUTSIDE the run set (`load_workshop_or_404` admits the creator, an admin, or a
        // DesignWorkshopViewer grant — a professor is none of those by role). Reusing
        // `canViewDesignWorkshopData` for the tap, which is the natural mistake because it is the
        // predicate the bucket itself is gated on, would hand them a tappable row leading to a 404.
        val professor = user("PROFESSOR")
        assertTrue(
            "a professor must keep the search chip",
            FieldPermissions.canViewDesignWorkshopData(professor)
        )
        assertFalse(
            "a professor must not be handed a tappable design-workshop row",
            SearchDesignWorkshopRoute.offeredTo(professor)
        )
        // And the other half, which is why this is not simply the read predicate negated: a DESIGNER
        // may open a workshop and is outside the read set, so they never see the bucket to tap.
        val designer = user("DESIGNER")
        assertTrue(SearchDesignWorkshopRoute.offeredTo(designer))
        assertFalse(FieldPermissions.canViewDesignWorkshopData(designer))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // What is SAID when there is no route. Silence was never the alternative to a wrong guess.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown record type is refused by name rather than opened as something else`() {
        // A bucket the server grew after this APK shipped. The old mapper answered ARTISAN for it;
        // the sentence must name the type back so a researcher can report WHICH row did nothing.
        val line = unroutableRecordLine("gizmo")
        assertTrue("the refusal must name the type it refused: $line", "gizmo" in line)
        assertTrue("and must offer the way that does work: $line", "web portal" in line)
    }

    @Test
    fun `the design-workshop refusal names no menu row, because its reader has none`() {
        // The only account that can reach this sentence is one that SEES design-workshop rows and
        // cannot open a workshop — a professor. "Open these from Design workshops", which is what the
        // search screen prints under an inert bucket, names a menu row gated on
        // `can_run_design_workshops` that this reader does not have. Repeating it here would send
        // them looking for a row that is not in their drawer.
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

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The parts that are file-private to MainActivity, read from the source.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * `MainActivity.kt`, with its line endings NORMALISED TO LF before anybody slices it.
     *
     * The tree is CRLF. Every assertion below matches on multi-line fragments, and a slice taken on
     * a literal `"\n}"` finds nothing in a CRLF file — so the test would pass on an empty haystack
     * and prove exactly nothing. Normalising once, here, is what stops that.
     */
    private fun mainActivitySource(): String = repoFile(
        "src/main/java/com/designprototype/workshop/MainActivity.kt",
        "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
    ).readText().replace("\r\n", "\n")

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same helper, same reasoning, as [DesignWorkshopCardTest].
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

    /** The body of the mapper alone, so the header's quotation of the old defect is not matched. */
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
        // THE ASSERTION THIS WHOLE FILE EXISTS FOR. `else -> EntryMode.ARTISAN` is the line that
        // opened a design workshop as an artisan; any `else` answering a record type at all is the
        // same defect waiting on the next unmapped string, so the arm is pinned rather than the one
        // value that happened to be wrong.
        assertFalse(
            "the mapper's else must not answer with a record type:\n$body",
            Regex("""else\s*->\s*EntryMode\.""").containsMatchIn(body)
        )
        assertTrue("the mapper's else must be null:\n$body", "else -> null" in body)
    }

    @Test
    fun `the design workshop is refused by an arm of its own, not by falling through`() {
        // Written out so that the reason sits at the line somebody would otherwise "complete" by
        // inventing an EntryMode for it — every EntryMode opens a record FORM, and a workshop is
        // twenty-two stages behind Screen.DesignWorkshopStages. Falling through to `else` would
        // produce the same null today and lose the explanation that stops it being reintroduced.
        val body = searchRecordEntryModeBody()
        assertTrue(
            "designWorkshop must be named in the mapper:\n$body",
            "SearchRecordTypes.DESIGN_WORKSHOP -> null" in body
        )
    }

    @Test
    fun `the three surfaces share one arm rather than three copies of it`() {
        val source = mainActivitySource()
        // `RecordCodeLookupPanel`'s mount requires its arm to be the search screen's own "character
        // for character". That was three separate lambdas held together by three comments; a shared
        // value is the only spelling of that requirement that a maintainer cannot quietly break.
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
        // The one line two lanes could not write. Passing anything else here — a lambda built inline,
        // or one that skipped the permission — would rebuild the gate a second time in a second place.
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
        // Both readers — the screen's own callback and openSearchRecord's backstop — invoke one
        // value, so the destination cannot be reached without passing the permission that guards it.
        // A second construction beside the first is how the gate comes to cover only one of them.
        val definitions = Regex("""val openDesignWorkshopFromSearch""").findAll(source).count()
        assertEquals("openDesignWorkshopFromSearch must be defined once", 1, definitions)
        assertTrue(
            "the route must be null for an account outside SearchDesignWorkshopRoute",
            "if (SearchDesignWorkshopRoute.offeredTo(user)) {" in source
        )
    }
}
