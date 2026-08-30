package com.designprototype.workshop.ui

import com.designprototype.workshop.data.AccessRosterDto
import com.designprototype.workshop.data.DesignerRosterDto
import com.designprototype.workshop.data.ROLE_MATCH_READ_LIMIT
import com.designprototype.workshop.data.RosterInstitutionsDto
import com.designprototype.workshop.data.RosterPageDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * THE FOUR BINDING RULES OF DROPDOWN_DESIGN §4.6, AS TESTS — one per rule, each one a rule somebody
 * already broke once.
 *
 * These are the Android half of the sweep whose backend twin is `backend/tests/test_roster_filters.py`
 * and whose web twin is `frontend/e2e/roster-filters-unit.spec.ts`. Like both of those, most of it is
 * pure assertion over source and over a query builder rather than anything reachable from a tap:
 * there is no `ui-test-junit4` and no Robolectric in `app/build.gradle.kts`, so the JVM suite cannot
 * render a control to look at it. That is exactly why the rules that matter are pure functions —
 * `rosterQueryParams`, `toggledAccessStatus`, `roleMatchCutNotice` — instead of expressions inside a
 * composable that only a person looking at a screen can exercise.
 *
 * ⚠ THE ORDER OF THE CLASSES BELOW IS THE ORDER OF THE RULES, and it is not alphabetical for a
 * reason: rule (i) is the one every other rule assumes. If "empty means everything by absence" ever
 * breaks, every default state in this file is testing the wrong request.
 */
class RosterFilterWireTest {

    /** `ApiClient.retrofit`'s configuration, copied so the test decodes exactly as the app does. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    /** A fixed clock. Presets resolve against the device's own day, so a test must pin one. */
    private val today = LocalDate.of(2026, 8, 30)

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // (i) EMPTY MEANS EVERYTHING, BY ABSENCE — never by an all-ticked state
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the default state of either screen sends no filter at all`() {
        // THE GUARANTEE THE WHOLE OF RULE (ii) RESTS ON. If this ever produces a key, the first
        // request a screen makes stops being the request it made before requirement 30 — and the
        // first thing that would go missing from it is the suspended row an admin came to find.
        RosterKind.entries.forEach { kind ->
            val query = rosterQueryParams(kind, emptyRosterFilters(kind), today)
            assertNull("$kind search", query.search)
            assertNull("$kind status", query.status)
            assertNull("$kind standing", query.standing)
            assertNull("$kind roles", query.roles)
            assertNull("$kind institutions", query.institutions)
            assertNull("$kind dateField", query.dateField)
            assertNull("$kind dateFrom", query.dateFrom)
            assertNull("$kind dateTo", query.dateTo)
            // The server's own default order, so it is left off the wire entirely.
            assertNull("$kind sort", query.sort)
            assertNull("$kind dir", query.dir)
            assertTrue("$kind must risk nothing", query.newGrammarKeys.isEmpty())
        }
    }

    @Test
    fun `ticking every tier is not the same request as ticking none`() {
        // THE RESERVED NINTH OPTION IS DOING ITS JOB OR THIS FAILS. `admitRole IS NULL` means "the
        // platform default, the lowest rung", and ticking all eight named tiers EXCLUDES every such
        // row. If the two requests were equal, the reserved option would be unnecessary — and its
        // absence is the failure `UNASSIGNED_WORKSHOP` was invented for.
        val everyTier = emptyRosterFilters(RosterKind.ACCESS)
            .copy(roles = ROSTER_ROLE_LADDER.toSet())
        val nothing = emptyRosterFilters(RosterKind.ACCESS)

        val ticked = rosterQueryParams(RosterKind.ACCESS, everyTier, today).roles
        val untouched = rosterQueryParams(RosterKind.ACCESS, nothing, today).roles

        assertNull("nothing ticked must be ABSENT, never a list of everything", untouched)
        assertNotNull(ticked)
        assertNotEquals(untouched, ticked)
        assertFalse(
            "the reserved default-tier row is not one of the eight and must not appear",
            ticked!!.contains(ADMIT_ROLE_DEFAULT)
        )
    }

    @Test
    fun `blank and all-blank tokens do not filter`() {
        // `resolve_workshop_ids:65-67`'s rule, on this side of the wire: absent, empty and all-blank
        // are one state, and it is "do not filter". A `""` here would reach the server as `?roles=`,
        // which it reads the same way — but it is not what "there is no such filter" looks like
        // anywhere else in this app, and it breaks the byte-identity of the default request.
        val blanks = emptyRosterFilters(RosterKind.DESIGNER).copy(roles = setOf("", "   "))
        assertNull(rosterQueryParams(RosterKind.DESIGNER, blanks, today).roles)
        assertNull(tokenList(emptySet(), ROSTER_ROLE_LADDER))
        assertNull(institutionList(setOf(" ", "")))
    }

    @Test
    fun `one status is spelled exactly as it was before requirement 30`() {
        // THE COMPATIBILITY CLAUSE OF §4.1, PINNED. `status` was a single value on this wire for as
        // long as the screen has existed, and the pending queue still asks `?status=PENDING` by hand.
        // A lone value must therefore stay byte-identical as the parameter becomes plural, or the
        // queue and the list would be asking two different questions of one column.
        val onlyWaiting = emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("PENDING"))
        assertEquals("PENDING", rosterQueryParams(RosterKind.ACCESS, onlyWaiting, today).status)
    }

    @Test
    fun `several statuses are comma-joined in one canonical order`() {
        // CANONICAL, so the same two ticks cannot produce two query strings depending on which was
        // tapped first — and COMMA-JOINED rather than repeated, because a repeated key against a
        // server that still declares `status` as one string is silently reduced to its LAST value:
        // a wrong answer dressed as a correct one. The comma earns a 422 instead, which is a refusal
        // a reader can see.
        val one = emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("SUSPENDED", "PENDING"))
        val other = emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("PENDING", "SUSPENDED"))

        assertEquals("PENDING,SUSPENDED", rosterQueryParams(RosterKind.ACCESS, one, today).status)
        assertEquals(
            rosterQueryParams(RosterKind.ACCESS, one, today).status,
            rosterQueryParams(RosterKind.ACCESS, other, today).status
        )
    }

    @Test
    fun `ticking the fourth standing chip collapses to Everyone`() {
        // All four ticked and none ticked return the same rows, so only one of them may be
        // expressible. On a row of chips the all-ticked state is four taps away, so it is collapsed
        // the moment it is reached — see `toggledAccessStatus`.
        var chosen = emptySet<String>()
        ACCESS_STATUS_TOKENS.dropLast(1).forEach { chosen = toggledAccessStatus(chosen, it) }
        assertEquals(3, chosen.size)

        chosen = toggledAccessStatus(chosen, ACCESS_STATUS_TOKENS.last())
        assertTrue("the fourth tick is the widest state, not a fourth filter", chosen.isEmpty())
    }

    @Test
    fun `an unknown token still reaches the server rather than being dropped here`() {
        // NOT THIS CLIENT'S TO JUDGE. The server answers an unknown token with a 422 naming the valid
        // values, which is a visible refusal; dropping it here would silently answer a NARROWER
        // question than the control asked and look exactly like the filter working.
        val odd = emptyRosterFilters(RosterKind.ACCESS).copy(roles = setOf("DESIGNER", "ZZZ_TIER"))
        assertEquals("DESIGNER,ZZZ_TIER", rosterQueryParams(RosterKind.ACCESS, odd, today).roles)
    }

    @Test
    fun `a key that does not belong to a route is never sent to it`() {
        // An undeclared parameter is either ignored — so the filter silently did nothing — or refused.
        // The first of those is indistinguishable from the filter being broken, which is why one
        // shared state object is only safe with this gate in it.
        val loaded = RosterFilters(
            status = setOf("ACTIVE"),
            standing = "suspended",
            institutions = setOf("NID"),
        )
        val access = rosterQueryParams(RosterKind.ACCESS, loaded, today)
        val designer = rosterQueryParams(RosterKind.DESIGNER, loaded, today)

        assertNotNull("status belongs to the allow-list", access.status)
        assertNull("standing does not", access.standing)
        assertNull("institutions do not — AccessRoster has no such column", access.institutions)

        assertNull("status does not belong to the designer roster", designer.status)
        assertNotNull(designer.standing)
        assertNotNull(designer.institutions)
    }

    @Test
    fun `the roster route declares every parameter, and activeOnly is not one of them`() {
        // EVERYTHING IN REQUIREMENT 30 FOR THE DESIGNER ROSTER IS CONTINGENT ON THIS ONE INTERFACE
        // CHANGE — §4.6 (iv) says so in those words. A control whose parameter is not declared on the
        // Retrofit interface silently does nothing, which is indistinguishable from the filter being
        // broken.
        //
        // AND `activeOnly` MUST NOT COME BACK. `standing` is the same question in the new grammar, and
        // sending both is a 422 rather than a silent winner. Sliced to the declaration rather than
        // swept over the file, because `GET /questionnaires` has an unrelated `activeOnly` of its own
        // — a file-wide ban would fail on somebody else's endpoint and teach the next reader to
        // delete this test.
        val declaration = withoutComments(
            repoFile("app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        ).substringAfter("suspend fun designerRoster(").substringBefore("): RosterPageDto")

        listOf("search", "standing", "roles", "institutions", "dateField", "dateFrom", "dateTo", "sort", "dir")
            .forEach { assertTrue("designerRoster must declare $it", declaration.contains(it)) }
        assertFalse("and must never declare activeOnly again", declaration.contains("activeOnly"))

        // The repository wrapper in front of it, likewise: it is where a stray `activeOnly = true`
        // default would be reintroduced without touching the interface at all.
        val wrapper = withoutComments(
            repoFile("app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt").readText()
        ).substringAfter("suspend fun designerRoster(").substringBefore("suspend fun designerRosterInstitutions")
        assertFalse("the repository must not send activeOnly", wrapper.contains("activeOnly"))
        assertTrue("and it must pass standing through", wrapper.contains("standing"))

        // And the query builder, which is the only thing that decides what goes on the wire at all.
        assertFalse(
            withoutComments(
                repoFile("app/src/main/java/com/designprototype/workshop/ui/RosterFilters.kt").readText()
            ).contains("activeOnly")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // (ii) SUSPENDED AND REFUSED ROWS STAY LISTED BY DEFAULT
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `no filter control defaults to a narrowing value`() {
        // An admin opens these screens BECAUSE somebody cannot sign in, and the row refusing them is
        // the REJECTED or SUSPENDED one. The rule is written down in four places already and a new
        // control must not contradict any of them.
        RosterKind.entries.forEach { kind ->
            val empty = emptyRosterFilters(kind)
            assertFalse("$kind opens narrowed", hasActiveRosterFilters(kind, empty))
            assertEquals(0, sheetFilterCount(kind, empty))
            assertTrue(empty.status.isEmpty())
            assertTrue(empty.standing.isEmpty())
            assertTrue(empty.roles.isEmpty())
            assertTrue(empty.institutions.isEmpty())
            assertEquals(RosterRange.ANY, empty.range)
        }
    }

    @Test
    fun `the widest standing row is the one the designer roster opens on`() {
        // `""` first and chosen by default — the ABSENT parameter, which the server reads as "both
        // standings". A control whose first row narrowed would put this screen's most-needed row out
        // of view for the exact admin who came to find it.
        assertEquals("", DESIGNER_STANDING_OPTIONS.first().value)
        assertEquals("", emptyRosterFilters(RosterKind.DESIGNER).standing)
        assertEquals(
            "the third row is the query this screen could not previously ask at all",
            "suspended",
            DESIGNER_STANDING_OPTIONS.last().value
        )
    }

    @Test
    fun `there is no hide-suspended control on either screen`() {
        // It would be a SECOND SPELLING of ticking the other options, and a filter with two spellings
        // for one state cannot tell a default from a deliberate choice. Rejected on rule (ii) as well:
        // a "hide suspended" defaulted ON is the failure this whole rule exists to prevent.
        val labels = ACCESS_STATUS_OPTIONS.map { it.label } + DESIGNER_STANDING_OPTIONS.map { it.label }
        labels.forEach { label ->
            assertFalse("no control may offer to hide anybody: $label", label.contains("Hide", true))
        }
    }

    @Test
    fun `clearing every filter keeps the order`() {
        // The button says "Clear every filter", and an order is not a filter: it narrows nothing and
        // hides nobody. An admin who sorted by "first signed in" to find outstanding invitations and
        // then clears a search is still asking that question.
        val set = emptyRosterFilters(RosterKind.DESIGNER).copy(
            search = "ravi",
            standing = "suspended",
            roles = setOf("DESIGNER"),
            institutions = setOf("NID"),
            range = RosterRange.LAST_30_DAYS,
            sort = "firstSeen",
            dir = RosterDir.DESC,
        )
        val cleared = clearRosterFilters(RosterKind.DESIGNER, set)

        assertFalse(hasActiveRosterFilters(RosterKind.DESIGNER, cleared))
        assertEquals("firstSeen", cleared.sort)
        assertEquals(RosterDir.DESC, cleared.dir)
    }

    @Test
    fun `clearing every filter empties the search box as well as the applied term`() {
        // THE SCREEN-LEVEL HALF OF THE SAME BUTTON, AND THE ONE THAT SHIPPED BROKEN.
        //
        // Each screen holds TWO pieces of search state on purpose: `search` is the keystroke and
        // `filters.search` is what the last request actually carried. Keeping them apart is what
        // makes "nobody matches X" safe to print — it can never name a term the server was not asked
        // about. `clearRosterFilters` blanks the APPLIED term, and until the two `onChange` lambdas
        // wrote the box as well the halves were left disagreeing in the other direction: the BOX
        // naming a term the server was not asked about, which is the identical lie read from the
        // other end.
        //
        // The sequence, exactly. An admin searches for a colleague, sees no rows, opens Filters and
        // presses "Clear every filter" to check whether a filter was hiding them. The request widens
        // to the whole roster; the box still shows the address; `hasActiveRosterFilters` is now false
        // so the clear-all button disappears and the empty arm switches from "nobody matches these
        // filters" to "nobody is on this list yet" — three sentences describing a screen other than
        // the one in front of them. And within the debounce window it is sharper still: the effect is
        // keyed on the box, so a clear-all pressed within 400 ms of the last keystroke left a
        // coroutine in flight that put the term BACK after the button said it had gone.
        //
        // Asserted over source because there is no Robolectric here and this is composable state —
        // the same trade the walk sweep below makes, and the reason the parts that CAN be pure
        // functions are.
        listOf(
            "app/src/main/java/com/designprototype/workshop/ui/AccessRosterScreen.kt",
            "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerRosterScreen.kt",
        ).forEach { path ->
            val sheet = withoutComments(repoFile(path).readText())
                .substringAfter("onChange = { next ->")
                .substringBefore("onDismiss =")
            assertTrue(
                "$path must put the cleared term back into its own search box",
                sheet.contains("search = next.search")
            )
            assertTrue("$path must still reset its pager", sheet.contains("page = 1"))
        }

        // And the value the box is being handed really is blank, on both rosters — the property the
        // screens are relying on.
        RosterKind.entries.forEach { kind ->
            val cleared = clearRosterFilters(kind, emptyRosterFilters(kind).copy(search = "ravi"))
            assertEquals("", cleared.search)
        }
    }

    @Test
    fun `a period set with no dates still counts as something to clear`() {
        // Read off the CONTROLS, not off the wire. "Custom range" with both boxes empty sends no date
        // keys — but the reader has visibly changed a control and must be able to put it back, or the
        // clear-all button disappears from a screen that does not look cleared.
        val custom = emptyRosterFilters(RosterKind.ACCESS).copy(range = RosterRange.CUSTOM)
        assertTrue(hasActiveRosterFilters(RosterKind.ACCESS, custom))
        assertNull("and it still narrows nothing", rosterQueryParams(RosterKind.ACCESS, custom, today).dateFrom)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // (iii) ANY CAP OR TRUNCATION IS STATED ON SCREEN, WITH THE NUMBER
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `a truncated role match is stated with the number and names the move that works`() {
        val said = roleMatchCutNotice(true)
        assertNotNull(said)
        assertTrue("the number is the whole point", said!!.contains("$ROLE_MATCH_READ_LIMIT"))
        assertTrue("it must say people are MISSING, not that a page is short", said.contains("missing"))
        assertTrue("every page, not only this one", said.contains("any page"))
        // THE MOVE THAT WORKS. The cut happened upstream of the search, in the account read, so
        // "narrow the search" — which is what the shared `flagCutNotice` would have said — cannot put
        // a single unread account back. Fewer tiers means fewer accounts to read.
        assertTrue(said.contains("fewer tiers"))
        assertFalse("never tell the reader to search: it cannot help", said.contains("search"))
    }

    @Test
    fun `a role match with no flag says nothing at all`() {
        // `null` is the wire's shape on a deployment that predates the key, and "we were told nothing"
        // must never be rendered as "we checked, and there is no cut".
        assertNull(roleMatchCutNotice(null))
        assertNull(roleMatchCutNotice(false))
        assertNull(accessRoleCutNotice(null))
        assertNull(accessRoleCutNotice(false))
        assertNull(institutionCutNotice(null, 200))
        assertNull(institutionCutNotice(false, 200))
    }

    @Test
    fun `the allow-list's tier cut does not borrow the designer roster's mechanism`() {
        // `AccessRoster.admitRole` IS a column, so matching a tier there needs no second read. Telling
        // an admin that "matching a tier means reading the accounts that hold it" would hand them a
        // mechanism that does not apply, and a reader given the wrong mechanism cannot act on it.
        val said = accessRoleCutNotice(true)
        assertNotNull(said)
        assertFalse("these rows are not designers", said!!.contains("designers"))
        assertFalse("and no number was read, so none is printed", said.contains("$ROLE_MATCH_READ_LIMIT"))
        assertTrue(said.contains("fewer tiers"))
    }

    @Test
    fun `the institution cap prints the count this client actually received`() {
        // NEVER PRINT A CAP YOU DID NOT READ. The endpoint's `take` is the server's to change, and a
        // stated cap that is not the enforced cap is worse than no sentence at all — so the number is
        // the count of names handed over, which is a fact this client can see.
        val said = institutionCutNotice(true, offered = 200)
        assertNotNull(said)
        assertTrue(said!!.contains("200"))
        // It points at the roster search box, which DOES reach the whole table — `search` is OR-ed
        // over `institution` on that route — rather than at the picker's own box, which does not.
        assertTrue(said.contains("search box above"))
        assertTrue("and an unknown count still says the fact", institutionCutNotice(true, 0)!!.isNotEmpty())
    }

    @Test
    fun `a server that ignores the filter grammar is named on screen, control by control`() {
        // FastAPI DROPS an undeclared query parameter in silence, so an unfiltered 200 comes back
        // under controls that say otherwise — R3 arriving through a version skew. The sentence names
        // WHICH controls did not reach the server, because "some filters were ignored" leaves the
        // admin unable to tell which part of what they are reading is an answer.
        val query = rosterQueryParams(
            RosterKind.DESIGNER,
            emptyRosterFilters(RosterKind.DESIGNER).copy(
                standing = "suspended",
                roles = setOf("DESIGNER"),
                sort = "name",
                dir = RosterDir.ASC,
            ),
            today,
        )
        assertEquals(listOf("standing", "roles", "order"), query.newGrammarKeys)

        val said = rosterFilterGrammarNotice(understood = false, sent = query.newGrammarKeys)
        assertNotNull(said)
        assertTrue(said!!.contains("standing, roles and order"))
        assertTrue("it must say the list is complete, only unfiltered", said.contains("Nothing is missing"))

        // SILENT BEFORE ANY ANSWER, and silent once one has been understood. Accusing a server that
        // has not spoken yet is the same class of error as the cut notices above.
        assertNull(rosterFilterGrammarNotice(null, query.newGrammarKeys))
        assertNull(rosterFilterGrammarNotice(true, query.newGrammarKeys))
        // And silent when nothing was at risk: `search` and a LONE `status` have been on both routes
        // since before requirement 30, so a request carrying only those is answered by every deployment.
        assertNull(rosterFilterGrammarNotice(false, emptyList()))
    }

    @Test
    fun `a comma-joined status counts as new grammar and a lone one does not`() {
        // FastAPI drops a parameter it does not DECLARE and refuses a value it cannot PARSE, and
        // `?status=PENDING,SUSPENDED` against a route that still takes one status is the second: a 422
        // naming four values the admin can see they ticked two of. The chips have to be nameable as
        // the likely cause, or that refusal reads as the product being broken.
        val two = rosterQueryParams(
            RosterKind.ACCESS,
            emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("PENDING", "SUSPENDED")),
            today,
        )
        assertEquals(listOf("standing"), two.newGrammarKeys)

        val one = rosterQueryParams(
            RosterKind.ACCESS,
            emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("PENDING")),
            today,
        )
        assertTrue("one status is the request this screen has always made", one.newGrammarKeys.isEmpty())
    }

    @Test
    fun `a refused read names the filters that most likely caused it`() {
        // The other half of the skew, and the reachable one today. It does not replace the server's
        // own words — the refusal may have been about something else — it says what the likely cause
        // is and names the move that gets a list back.
        val hint = rosterFilterRefusalHint(understood = null, sent = listOf("standing", "roles"))
        assertNotNull(hint)
        assertTrue(hint!!.contains("standing and roles"))
        assertTrue(hint.contains("Clearing them"))

        // A server that has answered with the flag once understands the grammar, so a later failure is
        // an ordinary one and this must not accuse it.
        assertNull(rosterFilterRefusalHint(understood = true, sent = listOf("roles")))
        assertNull(rosterFilterRefusalHint(understood = false, sent = emptyList()))
    }

    @Test
    fun `search and status are never counted as new grammar`() {
        val query = rosterQueryParams(
            RosterKind.ACCESS,
            emptyRosterFilters(RosterKind.ACCESS).copy(search = "ravi", status = setOf("SUSPENDED")),
            today,
        )
        assertNotNull(query.search)
        assertNotNull(query.status)
        assertTrue(
            "both have been accepted by these routes since before requirement 30",
            query.newGrammarKeys.isEmpty()
        )
    }

    @Test
    fun `an envelope from a deployment that predates the flag decodes as null, not false`() {
        // The three states of `roleMatchTruncated` are the whole of this client's ability to tell a
        // server that filters from one that silently does not.
        val old = json.decodeFromString<RosterPageDto<DesignerRosterDto>>(
            """{"items":[],"total":0,"page":1,"pageSize":50,"pages":0}"""
        )
        assertNull("absent must not coerce to false", old.roleMatchTruncated)

        val fresh = json.decodeFromString<RosterPageDto<DesignerRosterDto>>(
            """{"items":[],"total":0,"page":1,"pageSize":50,"pages":0,"roleMatchTruncated":false}"""
        )
        assertEquals(false, fresh.roleMatchTruncated)

        val cut = json.decodeFromString<RosterPageDto<AccessRosterDto>>(
            """{"items":[],"total":0,"page":1,"pageSize":50,"pages":0,"roleMatchTruncated":true}"""
        )
        assertEquals(true, cut.roleMatchTruncated)
    }

    @Test
    fun `a roster row still decodes through the new envelope`() {
        // The envelope changed type; the ROW did not, and a handset older or newer than the server
        // must still read it. Every field is defaulted for that reason.
        val page = json.decodeFromString<RosterPageDto<DesignerRosterDto>>(
            """
            {"items":[{"id":"r1","email":"c.iyer@example.org"}],
             "total":1,"page":1,"pageSize":50,"pages":1,"roleMatchTruncated":false}
            """.trimIndent()
        )
        assertEquals(1, page.items.size)
        assertTrue("a row with no isActive must read as able to sign in", page.items[0].isActive)
        assertNull(page.items[0].fullName)
    }

    @Test
    fun `the institutions endpoint's own cut flag survives an older deployment`() {
        val half = json.decodeFromString<RosterInstitutionsDto>("""{"items":["NID","NIFT"]}""")
        assertEquals(listOf("NID", "NIFT"), half.items)
        assertNull("absent is 'it said nothing', not 'nothing was cut'", half.truncated)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // (iv) FILTERING IS SERVER-SIDE
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the designer roster makes no on-device filter, sort or walk`() {
        // THE DELETED WALK, PINNED SHUT. `walkPagedListing` gathered 500 rows of a table of about
        // 1,300 and the screen then sorted and filtered them in Kotlin — so the box answered "no
        // match" about designers who exist, and the short read kept the NEWEST empanelments while
        // losing the OLDEST, which is the row the screen is opened for. All four pieces (the walk,
        // the sort, the filter and the notice describing the walk's truncation) went in one change,
        // because leaving any one behind leaves a screen saying something untrue about itself.
        val code = withoutComments(
            repoFile(
                "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerRosterScreen.kt"
            ).readText()
        )
        assertFalse("the walk is gone", code.contains("walkPagedListing"))
        assertFalse("and so is the device-side sort", code.contains("sortedWith"))
        assertFalse("and the device-side filter over the page", code.contains("rows.filter"))
        assertFalse("and its predicate", code.contains("fun DesignerRosterDto.matches"))
        assertFalse("and the notice describing a truncation that no longer happens", code.contains("rosterTruncated"))
        // NOT A BLANKET BAN ON `.filter`. `accountsByEmail` filters the DIRECTORY — shape
        // normalisation over a vocabulary of accounts, dropping rows with no id or no address that
        // could not be opened anyway — and that is one grep away from the thing forbidden here. The
        // sweep names the fetched page instead, which is the thing rule (iv) is actually about.
    }

    @Test
    fun `neither roster screen narrows a fetched page`() {
        // The invariant `admin/access/page.tsx:31-41` asserts in prose on the web and which now has a
        // test on both clients: there is no `.filter()` over a fetched page anywhere on these screens.
        // A client-side box over a server-truncated page answers "No matches" about records that exist.
        listOf(
            "app/src/main/java/com/designprototype/workshop/ui/AccessRosterScreen.kt",
            "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DesignerRosterScreen.kt",
            "app/src/main/java/com/designprototype/workshop/ui/RosterFilterBar.kt",
        ).forEach { path ->
            val code = withoutComments(repoFile(path).readText())
            assertFalse("$path narrows rows on the device", code.contains("rows.filter"))
            assertFalse("$path re-sorts rows on the device", code.contains("rows.sorted"))
        }
    }

    @Test
    fun `every filter the two screens offer reaches the wire`() {
        // Rule (iv) from the other end: a control that produced no query key would be a control that
        // silently did nothing, which is the failure the grammar notice exists to catch in a server
        // and which must be impossible in the client.
        val access = rosterQueryParams(
            RosterKind.ACCESS,
            RosterFilters(
                search = "ravi",
                status = setOf("SUSPENDED"),
                roles = setOf("DESIGNER", ADMIT_ROLE_DEFAULT),
                dateField = "requested",
                range = RosterRange.LAST_7_DAYS,
                sort = "attempts",
                dir = RosterDir.DESC,
            ),
            today,
        )
        assertEquals("ravi", access.search)
        assertEquals("SUSPENDED", access.status)
        assertEquals("DESIGNER,default", access.roles)
        assertEquals("requested", access.dateField)
        assertNotNull(access.dateFrom)
        assertNotNull(access.dateTo)
        assertEquals("attempts", access.sort)
        assertEquals("desc", access.dir)

        val designer = rosterQueryParams(
            RosterKind.DESIGNER,
            RosterFilters(
                standing = "suspended",
                roles = setOf(ROLE_NEVER_SIGNED_IN, "ADMIN"),
                institutions = setOf("NIFT", INSTITUTION_NONE, "NID"),
                dateField = "revoked",
                range = RosterRange.CUSTOM,
                from = LocalDate.of(2026, 1, 1),
                sort = "institution",
                dir = RosterDir.ASC,
            ),
            today,
        )
        assertEquals("suspended", designer.standing)
        assertEquals("ADMIN,never-signed-in", designer.roles)
        // Alphabetical, with the reserved NULL sentinel last — the same order the web sends, so the
        // two clients produce one query string for one set of ticks.
        assertEquals("NID,NIFT,none", designer.institutions)
        assertEquals("revoked", designer.dateField)
        assertNotNull("a range open at one end is still a range", designer.dateFrom)
        assertNull(designer.dateTo)
        assertEquals("institution", designer.sort)
        assertEquals("asc", designer.dir)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The order, the ladder and the clock — parity with the server and with the web
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the tier ladder is the platform's own, highest first`() {
        // THE FOURTH KOTLIN COPY OF THE LADDER, PINNED RATHER THAN TRUSTED. `FieldPermissions.RANKS`
        // is private to its file, so this list cannot be derived without editing a file this parcel
        // does not own — and both ways it can rot are silent. A tier added to the ladder and not here
        // simply has no row: the people holding it cannot be filtered for and nothing on screen reads
        // as broken. A token here the ladder does not know ranks 0, below a crowdsource volunteer.
        val ranks = ROSTER_ROLE_LADDER.map { FieldPermissions.rank(it) }
        assertEquals(8, ROSTER_ROLE_LADDER.size)
        assertEquals("every token must be one the ladder knows", ranks.sortedDescending(), ranks)
        assertFalse("a rank of 0 is a token this platform has never heard of", ranks.contains(0))
        ROSTER_ROLE_LADDER.forEach { role ->
            assertNotEquals(
                "an unlabelled tier renders as its UPPER_SNAKE token beside seven English ones",
                role,
                FieldPermissions.label(role)
            )
        }
    }

    @Test
    fun `the role picker never narrows to the viewer's own tier`() {
        // `assignableRoles` is right for the `admitRole` PICKER — you cannot grant a tier above your
        // own — and wrong for a FILTER: an admin must be able to filter for rows carrying a tier they
        // could not grant, or every master-admin row becomes invisible to every admin and the list
        // quietly stops being a complete answer for the person most likely to be auditing it.
        RosterKind.entries.forEach { kind ->
            val values = roleOptions(kind).map { it.value }
            assertTrue("$kind must offer the top of the ladder", values.contains("MASTER_ADMIN"))
            assertEquals("the ladder plus exactly one reserved row", 9, values.size)
            assertEquals(
                "and the reserved row is LAST — it is the absence of a tier, not one of them",
                if (kind == RosterKind.ACCESS) ADMIT_ROLE_DEFAULT else ROLE_NEVER_SIGNED_IN,
                values.last()
            )
        }
        // The two reserved tokens are NOT interchangeable, so a filter carried from one screen to the
        // other cannot silently ask a different question.
        assertNotEquals(ADMIT_ROLE_DEFAULT, ROLE_NEVER_SIGNED_IN)
    }

    @Test
    fun `every sort this client can send is one the route declares`() {
        // §4.3's two tables, and the 422 they prevent. `attempts` is a good access sort and a
        // meaningless designer one; sending it to the wrong route would refuse the whole list over a
        // token nothing on that screen even offers.
        assertEquals(
            listOf("added", "email", "name", "standing", "joined", "requested", "decided", "firstSeen", "attempts"),
            ACCESS_SORTS.keys.toList()
        )
        assertEquals(
            listOf("added", "email", "name", "institution", "firstSeen", "revoked"),
            DESIGNER_SORTS.keys.toList()
        )
        assertNull("attempts is not a designer sort", rosterSortSpec(RosterKind.DESIGNER, "attempts"))
        assertNull("institution is not an access sort", rosterSortSpec(RosterKind.ACCESS, "institution"))
        RosterKind.entries.forEach { kind ->
            assertNotNull("the default must exist on both", rosterSortSpec(kind, ROSTER_DEFAULT_SORT))
        }
    }

    @Test
    fun `choosing a new column takes that column's own first reading`() {
        // "Newest first" and "A to Z" are both the natural first reading of their own column and they
        // are opposite directions. Carrying `desc` from a date onto an email gives Z-to-A, which
        // nobody chose. Choosing the column already in use reverses it instead.
        val byDate = emptyRosterFilters(RosterKind.ACCESS)
        assertEquals(RosterDir.DESC, byDate.dir)
        assertEquals(RosterDir.ASC, nextSortDir(RosterKind.ACCESS, byDate, "email"))
        // The same column reverses instead: "added" is already the order, so DESC flips to ASC.
        assertEquals(RosterDir.ASC, nextSortDir(RosterKind.ACCESS, byDate, "added"))
        val byEmail = nextRosterSort(RosterKind.ACCESS, byDate, "email")
        assertEquals("email", byEmail.sort)
        assertEquals(RosterDir.ASC, byEmail.dir)
        assertEquals(RosterDir.DESC, nextRosterSort(RosterKind.ACCESS, byEmail, "email").dir)
    }

    @Test
    fun `a nullable column sorted newest-first says that blank rows come first`() {
        // Postgres puts NULLs FIRST on `desc`, and on `firstSeen` that IS the answer to "who have I
        // added who has not turned up" — the view the deleted device-side sort was built to produce.
        // A list opening on ten rows blank in the column just chosen reads as broken unless the
        // control said it would.
        val hint = sortRowHint(RosterKind.DESIGNER, emptyRosterFilters(RosterKind.DESIGNER), "firstSeen")
        assertNotNull(hint)
        assertTrue(hint!!.contains("newest first"))
        assertTrue("the outstanding-invitation view has to be named", hint.contains("no date"))

        val plain = sortRowHint(RosterKind.DESIGNER, emptyRosterFilters(RosterKind.DESIGNER), "email")
        assertEquals("A to Z", plain)
    }

    @Test
    fun `the date presets resolve against the device's own clock at request time`() {
        // A screen left open overnight must not keep asking about yesterday, which is why `today` is
        // a parameter and why `rosterQueryParams` is called inside the fetch rather than remembered.
        val (from, to) = resolveRosterRange(RosterRange.LAST_7_DAYS, null, null, today)
        assertNotNull(from)
        assertNotNull(to)
        // READ BACK IN THE DEVICE'S OWN ZONE, because that is what the bound was built in: comparing
        // the ISO string to a date would pass in UTC and fail in IST, where local midnight is the
        // previous afternoon in UTC. Inclusive of today, so "last 7 days" really is seven days.
        assertEquals(today.minusDays(6), Instant.parse(from).atZone(ZoneId.systemDefault()).toLocalDate())
        assertEquals(today, Instant.parse(to).atZone(ZoneId.systemDefault()).toLocalDate())

        // The end of a chosen day is 23:59:59 because the API compares with `lte`: a bare
        // start-of-day bound would drop every row created on the last day of the range.
        val (_, endOfToday) = resolveRosterRange(RosterRange.TODAY, null, null, today)
        val (_, endOfCustom) = resolveRosterRange(RosterRange.CUSTOM, null, today, today)
        assertEquals(endOfToday, endOfCustom)

        assertEquals(null to null, resolveRosterRange(RosterRange.ANY, null, null, today))
        // A custom range with nothing typed narrows nothing, so no key goes on the wire at all.
        assertEquals(null to null, resolveRosterRange(RosterRange.CUSTOM, null, null, today))
    }

    @Test
    fun `the preset tokens are the web's own ids`() {
        // A filter written down by either client has to read the same on the other. The Kotlin enum
        // names are this file's; the ids are the wire's.
        assertEquals(
            listOf("any", "today", "7d", "30d", "90d", "month", "year", "custom"),
            RosterRange.entries.map { it.id }
        )
    }

    @Test
    fun `the date columns are the two enums the routes declare`() {
        assertEquals(
            ACCESS_DATE_FIELDS,
            dateFieldOptions(RosterKind.ACCESS).map { it.value }
        )
        assertEquals(
            DESIGNER_DATE_FIELDS,
            dateFieldOptions(RosterKind.DESIGNER).map { it.value }
        )
        // The pre-selected column is read off the picker's own first row, so the two cannot drift.
        RosterKind.entries.forEach { kind ->
            assertEquals(dateFieldOptions(kind).first().value, emptyRosterFilters(kind).dateField)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The sentences the pickers inside the sheet print
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the institution picker tells the three empties apart`() {
        // §3.5, one control down. "Not fetched yet", "the read failed" and "the roster records none"
        // are three facts with three next moves, and the one this client is worst at — a failed read
        // drawn as an empty list — is the one that must never be worded as the third.
        assertEquals(
            loadingListLine("institutions"),
            institutionEmptyLine(InstitutionVocabulary.Loading)
        )
        assertEquals(
            couldNotListLine("institutions"),
            institutionEmptyLine(InstitutionVocabulary.Failed(online = true))
        )
        assertEquals(
            offlineListLine("institutions"),
            institutionEmptyLine(InstitutionVocabulary.Failed(online = false))
        )
        assertEquals(
            unscopedEmptyLine("institutions"),
            institutionEmptyLine(InstitutionVocabulary.Listed(emptyList(), truncated = false))
        )
        assertNull(institutionEmptyLine(InstitutionVocabulary.Listed(listOf("NID"), false)))

        // Three of the four say something different, and none of them claims non-existence except the
        // one reached from a read that answered.
        val sentences = listOf(
            institutionEmptyLine(InstitutionVocabulary.Loading),
            institutionEmptyLine(InstitutionVocabulary.Failed(true)),
            institutionEmptyLine(InstitutionVocabulary.Failed(false)),
            institutionEmptyLine(InstitutionVocabulary.Listed(emptyList(), false)),
        )
        assertEquals("no two states may share a sentence", sentences.size, sentences.toSet().size)
    }

    @Test
    fun `the reserved no-institution row is offered and cannot be duplicated`() {
        // Without it, ticking every institution silently drops every row that has none — the
        // `UNASSIGNED_WORKSHOP` failure. And `institution` is free text, so a roster whose institution
        // is literally called "none" would otherwise put two rows with one value in the picker.
        val rows = institutionOptions(listOf("NID", "none", " ", "NID"))
        assertEquals(
            "each value once, reserved row last",
            listOf("NID", INSTITUTION_NONE),
            rows.map { it.value }
        )
        assertEquals("No institution recorded", rows.last().label)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // Fixtures
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * A file anywhere at or above the test worker's directory.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. The same helper and the same reasoning as `DashboardTileParityTest`.
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
     * Drop line and block comments so a source sweep reads CODE.
     *
     * Every rule pinned by a sweep in this file is also EXPLAINED by a comment naming the thing it
     * forbids — "the device-side filter", "activeOnly" — so a sweep over the raw text would fail on
     * its own documentation. Nothing here needs string literals preserved, so the cheap reader is
     * enough.
     */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i)
                    i = if (end == -1) source.length else end
                }
                source.startsWith("/*", i) -> {
                    // Kotlin block comments NEST, unlike Java's, and getting that backwards ends a
                    // comment early and drags real code into the swept text.
                    var depth = 1
                    i += 2
                    while (i < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> { depth++; i += 2 }
                            source.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
