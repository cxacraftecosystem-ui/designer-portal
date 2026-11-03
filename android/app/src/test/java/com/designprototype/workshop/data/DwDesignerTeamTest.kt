package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDesignerTeamTest {

    private val a = "cmsvdesigner00000000000a"
    private val b = "cmsvdesigner00000000000b"
    private val c = "cmsvdesigner00000000000c"

    @Test
    fun `the named-designer cap is the viewer cap, and not a second copy of the number`() {
        assertEquals(DW_VIEWER_LIMIT, DW_MAX_NAMED_DESIGNERS)
    }

    @Test
    fun `an empty pick folds to null rather than to an empty string`() {
        assertNull(dwNamedDesignerId(""))
        assertNull(dwNamedDesignerId(null))
        assertNull(dwNamedDesignerId("   "))
        assertEquals(a, dwNamedDesignerId("  $a  "))
    }

    @Test
    fun `emptiness is Python's, not Kotlin's`() {
        assertNull("U+00A0 alone is not somebody", dwNamedDesignerId("\u00A0"))
        assertNull("U+202F alone is not somebody", dwNamedDesignerId("\u202F"))
    }

    @Test
    fun `nobody named is a real answer and stays empty`() {
        val resolved = dwNamedDesignerTeam(chosen = emptyList(), lead = "")
        assertNull(resolved.lead)
        assertTrue(resolved.team.isEmpty())
        assertNull(dwNamedDesignerTeam(chosen = null, lead = null).lead)
        assertTrue(dwNamedDesignerTeam(chosen = listOf("", "  "), lead = " ").team.isEmpty())
    }

    @Test
    fun `a lead with an empty selection is a team of one, which is what an old draft looks like`() {
        val resolved = dwNamedDesignerTeam(chosen = emptyList(), lead = a)
        assertEquals(a, resolved.lead)
        assertEquals(listOf(a), resolved.team)
    }

    @Test
    fun `an unticked lead is dropped and the first ticked is promoted`() {
        val resolved = dwNamedDesignerTeam(chosen = listOf(b, c), lead = a)
        assertEquals(b, resolved.lead)
        assertEquals(listOf(b, c), resolved.team)
        assertFalse("the removed designer must not come back", a in resolved.team)
    }

    @Test
    fun `a ticked lead is honoured and sorts first whatever order it was ticked in`() {
        val resolved = dwNamedDesignerTeam(chosen = listOf(a, b, c), lead = c)
        assertEquals(c, resolved.lead)
        assertEquals("the lead leads; the rest keep their order", listOf(c, a, b), resolved.team)
    }

    @Test
    fun `blanks are absent and duplicates collapse, exactly as the server folds them`() {
        val resolved = dwNamedDesignerTeam(chosen = listOf(a, "  ", a, "\u00A0", b), lead = null)
        assertEquals(listOf(a, b), resolved.team)
        assertEquals(a, resolved.lead)
    }

    @Test
    fun `the rule is idempotent, because three layers apply it to the same choice`() {
        val once = dwNamedDesignerTeam(listOf(b, a, c), lead = a)
        val twice = dwNamedDesignerTeam(once.team, lead = once.lead)
        assertEquals(once, twice)
    }

    @Test
    fun `one designer sends designerUserId alone, byte-for-byte the body that already shipped`() {
        val fields = dwDesignerCreateFields(chosen = listOf(a), lead = "")
        assertEquals(a, fields.designerUserId)
        assertNull("the new key must not go onto the wire uninvited", fields.designerUserIds)

        assertNull(dwDesignerCreateFields(chosen = emptyList(), lead = a).designerUserIds)
    }

    @Test
    fun `nobody named sends neither key`() {
        val fields = dwDesignerCreateFields(chosen = emptyList(), lead = "")
        assertNull(fields.designerUserId)
        assertNull(fields.designerUserIds)
    }

    @Test
    fun `several designers send both keys, lead first`() {
        val fields = dwDesignerCreateFields(chosen = listOf(b, a), lead = a)
        assertEquals(a, fields.designerUserId)
        assertEquals(listOf(a, b), fields.designerUserIds)
    }

    @Test
    fun `an empty list is never sent as an empty list`() {
        assertNull(dwDesignerCreateFields(chosen = listOf("", " "), lead = "").designerUserIds)
    }

    private fun wire(body: DesignWorkshopCreateBody): String =
        ApiClient.json.encodeToString(DesignWorkshopCreateBody.serializer(), body)

    @Test
    fun `an unnamed create posts the same bytes it posted before either field existed`() {
        val json = wire(DesignWorkshopCreateBody(title = "Bagru block printing"))
        assertFalse(json.contains("designerUserId"))
        assertFalse(json.contains("designerUserIds"))
    }

    @Test
    fun `a one-designer create carries the singular key and not the plural one`() {
        val fields = dwDesignerCreateFields(chosen = listOf(a), lead = "")
        val json = wire(
            DesignWorkshopCreateBody(
                title = "Bagru block printing",
                designerUserId = fields.designerUserId,
                designerUserIds = fields.designerUserIds,
            )
        )
        assertTrue(json.contains("\"designerUserId\":\"$a\""))
        assertFalse("this is the key that 422s an older API", json.contains("designerUserIds"))
    }

    @Test
    fun `a team create carries both keys with the lead first`() {
        val fields = dwDesignerCreateFields(chosen = listOf(b, a), lead = a)
        val json = wire(
            DesignWorkshopCreateBody(
                title = "Bagru block printing",
                designerUserId = fields.designerUserId,
                designerUserIds = fields.designerUserIds,
            )
        )
        assertTrue(json.contains("\"designerUserId\":\"$a\""))
        assertTrue(json.contains("\"designerUserIds\":[\"$a\",\"$b\"]"))
    }

    @Test
    fun `an empty list set by hand WOULD reach the wire, which is why the transport folds`() {
        val json = wire(DesignWorkshopCreateBody(title = "x", designerUserIds = emptyList()))
        assertTrue(json.contains("\"designerUserIds\":[]"))
    }

    @Test
    fun `already-chosen ids keep their positions and new ones arrive in the order they are drawn`() {
        val next = dwOrderedDesignerPicks(
            previous = listOf(c, a),
            picked = setOf(c, a, b),
            offered = listOf(a, b, c),
        )
        assertEquals(listOf(c, a, b), next)
    }

    @Test
    fun `unticking removes and does not reshuffle the rest`() {
        val next = dwOrderedDesignerPicks(
            previous = listOf(c, a, b),
            picked = setOf(c, b),
            offered = listOf(a, b, c),
        )
        assertEquals(listOf(c, b), next)
    }

    @Test
    fun `a pick the current search no longer offers survives`() {
        val next = dwOrderedDesignerPicks(
            previous = emptyList(),
            picked = setOf(a, b),
            offered = listOf(b),
        )
        assertTrue(a in next && b in next)
        assertEquals(2, next.size)
    }

    @Test
    fun `a draft from before the multi-select decodes into a team of one`() {
        val store = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"schemaVersion":2,"workshopId":"local-abc","title":"Bagru","designerUserId":"$a"}"""
        val draft = store.decodeFromString(WorkshopDraft.serializer(), old)

        assertEquals(a, draft.designerUserId)
        assertTrue("no rung was spent, so the key is simply absent", draft.designerUserIds.isEmpty())

        val resolved = dwNamedDesignerTeam(draft.designerUserIds, draft.designerUserId)
        assertEquals("the fortnight's designer is not lost", listOf(a), resolved.team)
        assertEquals(a, resolved.lead)
    }

    @Test
    fun `a courtyard draft remembers the whole team it was opened for`() {
        val store = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val before = WorkshopDraft(
            workshopId = "local-abc",
            title = "Bagru block printing",
            designerUserId = a,
            designerUserIds = listOf(a, b, c),
        )
        val after = store.decodeFromString(
            WorkshopDraft.serializer(),
            store.encodeToString(WorkshopDraft.serializer(), before)
        )
        assertEquals(listOf(a, b, c), after.designerUserIds)
        assertEquals(a, after.designerUserId)
    }

    @Test
    fun `a complete destination list carries no caveat`() {
        assertNull(dwAdoptCandidateNotice(offline = false, searched = false, listTruncated = false))
    }

    @Test
    fun `each way the list can be short is its own sentence with its own next move`() {
        val offline = dwAdoptCandidateNotice(offline = true, searched = false, listTruncated = false)!!
        val searched = dwAdoptCandidateNotice(offline = false, searched = true, listTruncated = false)!!
        val cut = dwAdoptCandidateNotice(offline = false, searched = false, listTruncated = true)!!

        assertNotEquals(offline, searched)
        assertNotEquals(searched, cut)
        assertNotEquals(offline, cut)
        assertTrue(offline.contains("Could not connect"))
        assertTrue("it must name the box, which this dialog covers", searched.contains("search box"))
        assertTrue(cut.contains("more workshops than this screen could read"))
    }

    @Test
    fun `only the offline caveat explains the held move, and it says the wait is free`() {
        val offline = dwAdoptCandidateNotice(offline = true, searched = false, listTruncated = false)!!
        assertTrue("it must say the move is held", offline.contains("Moving waits for a connection"))
        assertTrue(
            "and that nothing was going anywhere before signal in any case",
            offline.contains("nothing is sent before then"),
        )

        for (live in listOf(
            dwAdoptCandidateNotice(offline = false, searched = true, listTruncated = false)!!,
            dwAdoptCandidateNotice(offline = false, searched = false, listTruncated = true)!!,
        )) {
            assertFalse(
                "the button is live in this state; do not tell them to wait for signal",
                live.contains("waits for a connection"),
            )
        }
    }

    @Test
    fun `offline outranks the other two`() {
        assertEquals(
            dwAdoptCandidateNotice(offline = true, searched = false, listTruncated = false),
            dwAdoptCandidateNotice(offline = true, searched = true, listTruncated = true),
        )
    }

    @Test
    fun `an empty destination list names the two doors in, and says something different offline`() {
        val online = dwAdoptNoCandidatesMessage(offline = false)
        val offline = dwAdoptNoCandidatesMessage(offline = true)

        assertNotEquals(online, offline)
        assertTrue("it must not read as 'no workshops exist'", online.contains("named on it"))
        assertTrue("the second door", online.contains("join card"))
        assertTrue(online.contains("Nothing on this phone is at risk"))
        assertTrue(offline.contains("could not connect"))
        assertFalse(
            "with nothing read, this must make no claim about who is named on what",
            offline.contains("named on it"),
        )
    }
}
