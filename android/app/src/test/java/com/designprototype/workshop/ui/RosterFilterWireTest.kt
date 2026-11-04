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

class RosterFilterWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val today = LocalDate.of(2026, 8, 30)

    @Test
    fun `the default state of either screen sends no filter at all`() {
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
            assertNull("$kind sort", query.sort)
            assertNull("$kind dir", query.dir)
            assertTrue("$kind must risk nothing", query.newGrammarKeys.isEmpty())
        }
    }

    @Test
    fun `ticking every tier is not the same request as ticking none`() {
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
        val blanks = emptyRosterFilters(RosterKind.DESIGNER).copy(roles = setOf("", "   "))
        assertNull(rosterQueryParams(RosterKind.DESIGNER, blanks, today).roles)
        assertNull(tokenList(emptySet(), ROSTER_ROLE_LADDER))
        assertNull(institutionList(setOf(" ", "")))
    }

    @Test
    fun `one status is spelled exactly as it was before requirement 30`() {
        val onlyWaiting = emptyRosterFilters(RosterKind.ACCESS).copy(status = setOf("PENDING"))
        assertEquals("PENDING", rosterQueryParams(RosterKind.ACCESS, onlyWaiting, today).status)
    }

    @Test
    fun `several statuses are comma-joined in one canonical order`() {
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
        var chosen = emptySet<String>()
        ACCESS_STATUS_TOKENS.dropLast(1).forEach { chosen = toggledAccessStatus(chosen, it) }
        assertEquals(3, chosen.size)

        chosen = toggledAccessStatus(chosen, ACCESS_STATUS_TOKENS.last())
        assertTrue("the fourth tick is the widest state, not a fourth filter", chosen.isEmpty())
    }

    @Test
    fun `an unknown token still reaches the server rather than being dropped here`() {
        val odd = emptyRosterFilters(RosterKind.ACCESS).copy(roles = setOf("DESIGNER", "ZZZ_TIER"))
        assertEquals("DESIGNER,ZZZ_TIER", rosterQueryParams(RosterKind.ACCESS, odd, today).roles)
    }

    @Test
    fun `a key that does not belong to a route is never sent to it`() {
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
        val declaration = withoutComments(
            repoFile("app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        ).substringAfter("suspend fun designerRoster(").substringBefore("): RosterPageDto")

        listOf("search", "standing", "roles", "institutions", "dateField", "dateFrom", "dateTo", "sort", "dir")
            .forEach { assertTrue("designerRoster must declare $it", declaration.contains(it)) }
        assertFalse("and must never declare activeOnly again", declaration.contains("activeOnly"))

        val wrapper = withoutComments(
            repoFile("app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt").readText()
        ).substringAfter("suspend fun designerRoster(").substringBefore("suspend fun designerRosterInstitutions")
        assertFalse("the repository must not send activeOnly", wrapper.contains("activeOnly"))
        assertTrue("and it must pass standing through", wrapper.contains("standing"))

        assertFalse(
            withoutComments(
                repoFile("app/src/main/java/com/designprototype/workshop/ui/RosterFilters.kt").readText()
            ).contains("activeOnly")
        )
    }

    @Test
    fun `no filter control defaults to a narrowing value`() {
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
        val labels = ACCESS_STATUS_OPTIONS.map { it.label } + DESIGNER_STANDING_OPTIONS.map { it.label }
        labels.forEach { label ->
            assertFalse("no control may offer to hide anybody: $label", label.contains("Hide", true))
        }
    }

    @Test
    fun `clearing every filter keeps the order`() {
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

        RosterKind.entries.forEach { kind ->
            val cleared = clearRosterFilters(kind, emptyRosterFilters(kind).copy(search = "ravi"))
            assertEquals("", cleared.search)
        }
    }

    @Test
    fun `a period set with no dates still counts as something to clear`() {
        val custom = emptyRosterFilters(RosterKind.ACCESS).copy(range = RosterRange.CUSTOM)
        assertTrue(hasActiveRosterFilters(RosterKind.ACCESS, custom))
        assertNull("and it still narrows nothing", rosterQueryParams(RosterKind.ACCESS, custom, today).dateFrom)
    }

    @Test
    fun `a truncated role match is stated with the number and names the move that works`() {
        val said = roleMatchCutNotice(true)
        assertNotNull(said)
        assertTrue("the number is the whole point", said!!.contains("$ROLE_MATCH_READ_LIMIT"))
        assertTrue("it must say people are MISSING, not that a page is short", said.contains("missing"))
        assertTrue("every page, not only this one", said.contains("any page"))
        assertTrue(said.contains("fewer tiers"))
        assertFalse("never tell the reader to search: it cannot help", said.contains("search"))
    }

    @Test
    fun `a role match with no flag says nothing at all`() {
        assertNull(roleMatchCutNotice(null))
        assertNull(roleMatchCutNotice(false))
        assertNull(accessRoleCutNotice(null))
        assertNull(accessRoleCutNotice(false))
        assertNull(institutionCutNotice(null, 200))
        assertNull(institutionCutNotice(false, 200))
    }

    @Test
    fun `the allow-list's tier cut does not borrow the designer roster's mechanism`() {
        val said = accessRoleCutNotice(true)
        assertNotNull(said)
        assertFalse("these rows are not designers", said!!.contains("designers"))
        assertFalse("and no number was read, so none is printed", said.contains("$ROLE_MATCH_READ_LIMIT"))
        assertTrue(said.contains("fewer tiers"))
    }

    @Test
    fun `the institution cap prints the count this client actually received`() {
        val said = institutionCutNotice(true, offered = 200)
        assertNotNull(said)
        assertTrue(said!!.contains("200"))
        assertTrue(said.contains("search box above"))
        assertTrue("and an unknown count still says the fact", institutionCutNotice(true, 0)!!.isNotEmpty())
    }

    @Test
    fun `a server that ignores the filter grammar is named on screen, control by control`() {
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

        assertNull(rosterFilterGrammarNotice(null, query.newGrammarKeys))
        assertNull(rosterFilterGrammarNotice(true, query.newGrammarKeys))
        assertNull(rosterFilterGrammarNotice(false, emptyList()))
    }

    @Test
    fun `a comma-joined status counts as new grammar and a lone one does not`() {
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
        val hint = rosterFilterRefusalHint(understood = null, sent = listOf("standing", "roles"))
        assertNotNull(hint)
        assertTrue(hint!!.contains("standing and roles"))
        assertTrue(hint.contains("Clearing them"))

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

    @Test
    fun `the designer roster makes no on-device filter, sort or walk`() {
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
    }

    @Test
    fun `neither roster screen narrows a fetched page`() {
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
        assertEquals("NID,NIFT,none", designer.institutions)
        assertEquals("revoked", designer.dateField)
        assertNotNull("a range open at one end is still a range", designer.dateFrom)
        assertNull(designer.dateTo)
        assertEquals("institution", designer.sort)
        assertEquals("asc", designer.dir)
    }

    @Test
    fun `the tier ladder is the platform's own, highest first`() {
        val ranks = ROSTER_ROLE_LADDER.map { FieldPermissions.rank(it) }
        assertEquals(11, ROSTER_ROLE_LADDER.size)
        assertEquals("every token must be one the ladder knows", ranks.sortedDescending(), ranks)
        assertFalse("a rank of 0 is a token this platform has never heard of", ranks.contains(0))
        ROSTER_ROLE_LADDER.forEach { role ->
            assertNotEquals(
                "an unlabelled tier renders as its UPPER_SNAKE token beside ten English ones",
                role,
                FieldPermissions.label(role)
            )
        }
    }

    @Test
    fun `the role picker never narrows to the viewer's own tier`() {
        RosterKind.entries.forEach { kind ->
            val values = roleOptions(kind).map { it.value }
            assertTrue("$kind must offer the top of the ladder", values.contains("MASTER_ADMIN"))
            assertEquals("the ladder plus exactly one reserved row", 12, values.size)
            assertEquals(
                "and the reserved row is LAST — it is the absence of a tier, not one of them",
                if (kind == RosterKind.ACCESS) ADMIT_ROLE_DEFAULT else ROLE_NEVER_SIGNED_IN,
                values.last()
            )
        }
        assertNotEquals(ADMIT_ROLE_DEFAULT, ROLE_NEVER_SIGNED_IN)
    }

    @Test
    fun `every sort this client can send is one the route declares`() {
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
        val byDate = emptyRosterFilters(RosterKind.ACCESS)
        assertEquals(RosterDir.DESC, byDate.dir)
        assertEquals(RosterDir.ASC, nextSortDir(RosterKind.ACCESS, byDate, "email"))
        assertEquals(RosterDir.ASC, nextSortDir(RosterKind.ACCESS, byDate, "added"))
        val byEmail = nextRosterSort(RosterKind.ACCESS, byDate, "email")
        assertEquals("email", byEmail.sort)
        assertEquals(RosterDir.ASC, byEmail.dir)
        assertEquals(RosterDir.DESC, nextRosterSort(RosterKind.ACCESS, byEmail, "email").dir)
    }

    @Test
    fun `a nullable column sorted newest-first says that blank rows come first`() {
        val hint = sortRowHint(RosterKind.DESIGNER, emptyRosterFilters(RosterKind.DESIGNER), "firstSeen")
        assertNotNull(hint)
        assertTrue(hint!!.contains("newest first"))
        assertTrue("the outstanding-invitation view has to be named", hint.contains("no date"))

        val plain = sortRowHint(RosterKind.DESIGNER, emptyRosterFilters(RosterKind.DESIGNER), "email")
        assertEquals("A to Z", plain)
    }

    @Test
    fun `the date presets resolve against the device's own clock at request time`() {
        val (from, to) = resolveRosterRange(RosterRange.LAST_7_DAYS, null, null, today)
        assertNotNull(from)
        assertNotNull(to)
        assertEquals(today.minusDays(6), Instant.parse(from).atZone(ZoneId.systemDefault()).toLocalDate())
        assertEquals(today, Instant.parse(to).atZone(ZoneId.systemDefault()).toLocalDate())

        val (_, endOfToday) = resolveRosterRange(RosterRange.TODAY, null, null, today)
        val (_, endOfCustom) = resolveRosterRange(RosterRange.CUSTOM, null, today, today)
        assertEquals(endOfToday, endOfCustom)

        assertEquals(null to null, resolveRosterRange(RosterRange.ANY, null, null, today))
        assertEquals(null to null, resolveRosterRange(RosterRange.CUSTOM, null, null, today))
    }

    @Test
    fun `the preset tokens are the web's own ids`() {
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
        RosterKind.entries.forEach { kind ->
            assertEquals(dateFieldOptions(kind).first().value, emptyRosterFilters(kind).dateField)
        }
    }

    @Test
    fun `the institution picker tells the three empties apart`() {
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
        val rows = institutionOptions(listOf("NID", "none", " ", "NID"))
        assertEquals(
            "each value once, reserved row last",
            listOf("NID", INSTITUTION_NONE),
            rows.map { it.value }
        )
        assertEquals("No institution recorded", rows.last().label)
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
