package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO MAY OPEN A DESIGN WORKSHOP, AND WHOSE NAME IS ON IT — the two answers, and the wire between
 * them.
 *
 * ── WHY THIS FILE EXISTS ─────────────────────────────────────────────────────────────────────────
 *
 * A design workshop is visible ONLY to its creator, to admins, and to whoever holds a
 * `DesignWorkshopViewer` row — enforced in the QUERY on the list and in the loader on the single
 * read, which refuses with a 404 identical to a nonexistent id. A DESIGNER cannot create one, so
 * `createdById` never matches for them: the workshops a designer can see are exactly the ones they
 * were NAMED on. Naming is therefore not a preference, it is the whole of how somebody gets in —
 * and everything below is a way that could quietly stop being true.
 *
 * The second half is the opposite shape. Several people may open it; exactly ONE name is on it,
 * because stage 1 and stage 3 declare a single designer block and `report_meta` feeds the promoted
 * name into the .docx's `dc:creator`, which the file format cannot express as a list. So a rule that
 * silently merged the two questions would either lock designers out or put the wrong name on a
 * ministry document, and neither failure is visible from a screen.
 *
 * `named_designer_team` (backend/app/services/design_workshops.py) and `namedDesignerTeam`
 * (frontend/lib/designWorkshops.ts) are the same rule on the other two clients; these assertions are
 * written so that a divergence from either fails here rather than in a courtyard.
 */
class DwDesignerTeamTest {

    private val a = "cmsvdesigner00000000000a"
    private val b = "cmsvdesigner00000000000b"
    private val c = "cmsvdesigner00000000000c"

    // ── The cap ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The create's cap and the viewers PUT's cap are ONE NUMBER, not two that happen to match.
     *
     * `POST /design-workshops` and `PUT /design-workshops/{id}/viewers` write the same table, and the
     * server imports its create cap from `MAX_DESIGN_WORKSHOP_VIEWERS` rather than choosing a second
     * one. A create that accepted a set the "Designers on a workshop" screen would refuse is one list
     * with two rules, and the admin meets the disagreement as a 422 about a shape after building a
     * selection by hand. Written as an identity rather than as `assertEquals(100, …)` so that moving
     * one of them cannot leave the other behind.
     */
    @Test
    fun `the named-designer cap is the viewer cap, and not a second copy of the number`() {
        assertEquals(DW_VIEWER_LIMIT, DW_MAX_NAMED_DESIGNERS)
    }

    // ── dwNamedDesignerId ────────────────────────────────────────────────────────────────────────

    /**
     * "Not decided yet" is nobody named, and it is NULL rather than an empty string.
     *
     * `ApiClient.json` leaves a null off the wire entirely, so a workshop with nobody named posts the
     * same bytes it posted before the field existed — which is what keeps it additive for a server
     * that has never heard of it (`APIModel` is `extra="forbid"`). And the same value is written to
     * the disk, where "" and null would be two spellings of one state for every later pass to
     * disagree about.
     */
    @Test
    fun `an empty pick folds to null rather than to an empty string`() {
        assertNull(dwNamedDesignerId(""))
        assertNull(dwNamedDesignerId(null))
        assertNull(dwNamedDesignerId("   "))
        assertEquals(a, dwNamedDesignerId("  $a  "))
    }

    /**
     * Emptiness is PYTHON'S, so the phone and the server cannot disagree about who was named.
     *
     * The server folds with `(payload.designerUserId or "").strip() or None`, and Python calls the
     * no-break space U+00A0 and the narrow no-break space U+202F whitespace while `Char.isWhitespace`
     * deliberately does not. A value that means "nobody" up there and "somebody" down here is exactly
     * the disagreement this field exists to end.
     */
    @Test
    fun `emptiness is Python's, not Kotlin's`() {
        assertNull("U+00A0 alone is not somebody", dwNamedDesignerId("\u00A0"))
        assertNull("U+202F alone is not somebody", dwNamedDesignerId("\u202F"))
    }

    // ── dwNamedDesignerTeam ──────────────────────────────────────────────────────────────────────

    @Test
    fun `nobody named is a real answer and stays empty`() {
        val resolved = dwNamedDesignerTeam(chosen = emptyList(), lead = "")
        assertNull(resolved.lead)
        assertTrue(resolved.team.isEmpty())
        assertNull(dwNamedDesignerTeam(chosen = null, lead = null).lead)
        assertTrue(dwNamedDesignerTeam(chosen = listOf("", "  "), lead = " ").team.isEmpty())
    }

    /**
     * With nothing ticked, a LEAD STANDING ALONE IS THE TEAM.
     *
     * NOT A HYPOTHETICAL. A draft written by a build that predates the multi-select carries exactly
     * this shape — a `designerUserId` and an empty `designerUserIds` — and it can sit on a handset in
     * a drawer for a fortnight before its create goes out. Reading the "the lead must be one of the
     * ticked" rule over it would drop the designer that fortnight was opened for, silently, and the
     * workshop would arrive on the server with nobody able to open it but its creator.
     */
    @Test
    fun `a lead with an empty selection is a team of one, which is what an old draft looks like`() {
        val resolved = dwNamedDesignerTeam(chosen = emptyList(), lead = a)
        assertEquals(a, resolved.lead)
        assertEquals(listOf(a), resolved.team)
    }

    /**
     * With designers ticked, the LEAD MUST BE ONE OF THEM — an unticked lead is dropped, not re-added.
     *
     * An admin who names a lead and then UNTICKS them has REMOVED that designer, and the workshop is
     * visible only to the people on it. Putting them back because the lead field still holds their id
     * would return access after it was taken away, which is the one direction an access control must
     * never drift. The first ticked is promoted instead — never the admin who pressed create, which
     * is the whole reason `designerUserId` exists.
     */
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

    /** Folding an already-folded answer changes nothing — the screen, the disk and the wire all ask. */
    @Test
    fun `the rule is idempotent, because three layers apply it to the same choice`() {
        val once = dwNamedDesignerTeam(listOf(b, a, c), lead = a)
        val twice = dwNamedDesignerTeam(once.team, lead = once.lead)
        assertEquals(once, twice)
    }

    // ── dwDesignerCreateFields, and the skew it exists to survive ────────────────────────────────

    /**
     * **THE MOST IMPORTANT ASSERTION IN THIS FILE: one designer sends the OLD BODY, unchanged.**
     *
     * `DesignWorkshopCreateBody` is an `APIModel`, which is `extra="forbid"`. An API deployed before
     * `designerUserIds` existed answers 422 `extra_forbidden` to a body that merely CARRIES the key,
     * whatever is in it — and this app ships separately from the API, so a handset updates when it
     * next sees wifi while the server updates when somebody deploys it.
     *
     * On this client that is not a refused request, it is a lost fortnight: a 4xx is never queued and
     * `WorkshopSync`'s create arm reads a 422 as a REFUSAL, so an ordinary offline create — one
     * designer, started in a courtyard — would come back permanently refused for a key the admin
     * never asked for. The new key travels only when there is genuinely a second designer, which is
     * to say only when the admin has asked for something an older server could not do anyway.
     */
    @Test
    fun `one designer sends designerUserId alone, byte-for-byte the body that already shipped`() {
        val fields = dwDesignerCreateFields(chosen = listOf(a), lead = "")
        assertEquals(a, fields.designerUserId)
        assertNull("the new key must not go onto the wire uninvited", fields.designerUserIds)

        // And the same answer from the other direction: a lead alone, which is what an old draft holds.
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

    /**
     * An EMPTY LIST is never sent. `[]` reads on the wire as "I considered this and the answer is
     * none", which is a different sentence from silence and one the server would have to interpret.
     */
    @Test
    fun `an empty list is never sent as an empty list`() {
        assertNull(dwDesignerCreateFields(chosen = listOf("", " "), lead = "").designerUserIds)
    }

    // ── What actually reaches the wire ──────────────────────────────────────────────────────────

    private fun wire(body: DesignWorkshopCreateBody): String =
        ApiClient.json.encodeToString(DesignWorkshopCreateBody.serializer(), body)

    /**
     * A null property is OMITTED from the body, not sent as `null` — which is the mechanism the whole
     * skew argument rests on.
     *
     * `ApiClient.json` sets `explicitNulls = false` AND leaves `encodeDefaults` at kotlinx's default
     * of false, two independent reasons. Asserted rather than assumed, because either flag could be
     * changed by somebody fixing an unrelated decode and the failure would be silent here and fatal
     * in a courtyard.
     */
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

    /**
     * THE HAZARD THE NORMALISER EXISTS FOR, pinned so nobody removes it as tidying.
     *
     * An empty list set on the body EXPLICITLY is not the property's default, so kotlinx encodes it —
     * `"designerUserIds":[]` reaches an API that would 422 the key's mere presence. Nothing stops a
     * caller from building that body, which is why `WorkshopRepository.createDesignWorkshop` folds
     * every body through [dwDesignerCreateFields] on the way out rather than trusting its two callers
     * (the create dialog and, a fortnight later, the sync pass) to agree.
     */
    @Test
    fun `an empty list set by hand WOULD reach the wire, which is why the transport folds`() {
        val json = wire(DesignWorkshopCreateBody(title = "x", designerUserIds = emptyList()))
        assertTrue(json.contains("\"designerUserIds\":[]"))
    }

    // ── The picker's order ──────────────────────────────────────────────────────────────────────

    /**
     * The sheet hands back a `Set`, which promises no order — and with no explicit lead the FIRST of
     * the team is whose profile stage 1 carries. So the screen keeps a list and this maintains it.
     */
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

    /**
     * A ticked id the current answer no longer offers is APPENDED, never dropped.
     *
     * A search REPLACES the offered list, so an admin who found a colleague under one surname, ticked
     * them, then typed a second surname would otherwise lose the first pick out of the selection
     * while the trigger still counted them. On this field a silent absence is a designer who cannot
     * open the workshop.
     */
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

    // ── The draft on the disk ───────────────────────────────────────────────────────────────────

    /**
     * A draft written by ANY EARLIER BUILD decodes with an empty team, and that reads as "the lead
     * alone, or nobody" rather than as "no designers".
     *
     * Additive and defaulted, so no [WORKSHOP_DRAFT_SCHEMA_VERSION] rung is owed by that constant's
     * own rule — and the field is deliberately NOT seeded from `designerUserId` on decode, because
     * two copies of one fact disagree the day somebody unticks the lead and the stale one is what the
     * create would send. [dwNamedDesignerTeam] is what makes the pair read correctly.
     */
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

    /** And a draft written by a build that HAS the field survives a round trip with its team intact. */
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

    // ── The link control's caveats ──────────────────────────────────────────────────────────────

    /**
     * A WHOLE list says nothing. Silence is the common and correct answer, and a standing caveat on
     * every visit is the padding this app has twice been asked not to have.
     */
    @Test
    fun `a complete destination list carries no caveat`() {
        assertNull(dwAdoptCandidateNotice(offline = false, searched = false, listTruncated = false))
    }

    /**
     * Three different facts, three different next moves, and NEVER the same sentence twice.
     *
     * "Move into a workshop" is one-way and unrepeatable, so a destination list that is quietly a
     * prefix is the most expensive absence on this screen: a designer who cannot find the workshop an
     * admin made an hour ago concludes the admin never made it. Each caveat therefore names what to
     * DO — with no connection there is nothing to do but come back with one; a search is cleared; a
     * truncated walk is narrowed by searching, which is the opposite instruction and must not be
     * given to somebody who has already typed something.
     */
    @Test
    fun `each way the list can be short is its own sentence with its own next move`() {
        val offline = dwAdoptCandidateNotice(offline = true, searched = false, listTruncated = false)!!
        val searched = dwAdoptCandidateNotice(offline = false, searched = true, listTruncated = false)!!
        val cut = dwAdoptCandidateNotice(offline = false, searched = false, listTruncated = true)!!

        assertNotEquals(offline, searched)
        assertNotEquals(searched, cut)
        assertNotEquals(offline, cut)
        assertTrue(offline.contains("could not be reached"))
        assertTrue("it must name the box, which this dialog covers", searched.contains("search box"))
        assertTrue(cut.contains("more workshops than this screen could read"))
    }

    /**
     * OFFLINE WINS, because it is the only state under which the list is not the server's answer at
     * all. A designer with no signal who is told "clear your search" would clear it and see the same
     * rows.
     */
    @Test
    fun `offline outranks the other two`() {
        assertEquals(
            dwAdoptCandidateNotice(offline = true, searched = false, listTruncated = false),
            dwAdoptCandidateNotice(offline = true, searched = true, listTruncated = true),
        )
    }

    /**
     * "Nothing to move it into" is a claim about ACCESS now, not about existence — and offline it is
     * not a claim at all.
     *
     * Since a workshop is visible only to the designers named on it, the common cause of an empty
     * destination list is an admin who created the workshop and did not tick this designer. The
     * sentence has to name both doors out of that: being named, and the join card. Telling somebody
     * with no signal to go and ask an admin for a workshop the admin already made is how a person
     * walks up a hill for nothing.
     */
    @Test
    fun `an empty destination list names the two doors in, and says something different offline`() {
        val online = dwAdoptNoCandidatesMessage(offline = false)
        val offline = dwAdoptNoCandidatesMessage(offline = true)

        assertNotEquals(online, offline)
        assertTrue("it must not read as 'no workshops exist'", online.contains("named on it"))
        assertTrue("the second door", online.contains("join card"))
        assertTrue(online.contains("Nothing on this phone is at risk"))
        assertTrue(offline.contains("could not be reached"))
        assertFalse(
            "with nothing read, this must make no claim about who is named on what",
            offline.contains("named on it"),
        )
    }
}
