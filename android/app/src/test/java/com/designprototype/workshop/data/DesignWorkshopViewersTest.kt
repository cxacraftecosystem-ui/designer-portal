package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignWorkshopViewersTest {

    private val creator = "u-creator"

    private val reader = "u-reader"

    private fun eligible(id: String, name: String = id, role: String = "DESIGNER") =
        DwEligibleViewerDto(id = id, name = name, email = "$id@example.org", role = role)

    private fun granted(id: String, name: String = id, role: String = "DESIGNER") =
        DwViewerDto(userId = id, name = name, email = "$id@example.org", role = role)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun decode(raw: String) =
        json.decodeFromString(DwEligibleViewerListDto.serializer(), raw)

    @Test
    fun `the creator is offered by neither list`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible(creator), eligible("u-b")),
            viewers = listOf(granted(creator), granted("u-b")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-b"), choices.map { it.userId })
    }

    @Test
    fun `a viewer the server no longer offers is still offered here, ticked and marked`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible("u-active")),
            viewers = listOf(granted("u-suspended")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-active", "u-suspended"), choices.map { it.userId })
        assertFalse(choices.first { it.userId == "u-active" }.grantedButIneligible)
        assertTrue(
            "a granted account missing from the eligible list must be marked, or an admin cannot " +
                "tell it from an ordinary option",
            choices.first { it.userId == "u-suspended" }.grantedButIneligible
        )
    }

    @Test
    fun `an eligible list that never arrived would call the whole team ineligible`() {
        val choices = dwViewerChoices(
            eligible = emptyList(),
            viewers = listOf(granted("u-a"), granted("u-b")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-a", "u-b"), choices.map { it.userId })
        assertTrue(
            "with no eligible list every holder is marked ineligible, which is why a failed load " +
                "must stop rather than fall through",
            choices.all { it.grantedButIneligible }
        )
    }

    @Test
    fun `somebody who is both eligible and granted appears once`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible("u-a"), eligible("u-b")),
            viewers = listOf(granted("u-b")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-a", "u-b"), choices.map { it.userId })
        assertFalse(choices.first { it.userId == "u-b" }.grantedButIneligible)
    }

    @Test
    fun `a blank id is not an option`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible(""), eligible("u-a")),
            viewers = listOf(granted("")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-a"), choices.map { it.userId })
    }

    @Test
    fun `the order is the server's, not this client's`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible("u-z", name = "Zoya"), eligible("u-a", name = "Aarav")),
            viewers = emptyList(),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-z", "u-a"), choices.map { it.userId })
    }

    @Test
    fun `the admin reading is never offered to themselves, but a row they hold is kept unmarked`() {
        val choices = dwViewerChoices(
            eligible = listOf(eligible(reader, role = "ADMIN"), eligible("u-a")),
            viewers = listOf(granted(reader, role = "ADMIN"), granted("u-suspended")),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
            retained = listOf(eligible(reader, role = "ADMIN")),
        )

        assertEquals(listOf("u-a", reader, "u-suspended"), choices.map { it.userId })
        assertFalse(
            "the reader's own row is no evidence about the designer roster",
            choices.first { it.userId == reader }.grantedButIneligible
        )
        assertTrue(
            "the mark still says what it can prove about everybody else",
            choices.first { it.userId == "u-suspended" }.grantedButIneligible
        )

        val unheld = dwViewerChoices(
            eligible = listOf(eligible(reader, role = "ADMIN"), eligible("u-a")),
            viewers = emptyList(),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = true,
        )
        assertEquals(listOf("u-a"), unheld.map { it.userId })
    }

    @Test
    fun `the wire says when the list was cut, and a server that says nothing cuts nothing`() {
        val cut = decode(LIVE_CAPPED_PAGE)
        assertTrue("the cut has to reach the client, or only a server log knows", cut.truncated)
        assertEquals(2, cut.users.size)

        val whole = decode(LIVE_SEARCH_HIT)
        assertFalse(whole.truncated)
        assertEquals("Unrelated Designer Nabakalebara8a886916", whole.users.single().name)

        assertFalse(decode("""{"users":[]}""").truncated)
    }

    @Test
    fun `the sentence under the search box is one of four, and silence is one of them`() {
        assertNull(
            dwViewerOfferNotice(
                DwEligibleViewers(users = listOf(eligible("u-a")), truncated = false, search = null)
            )
        )
        assertNull(
            dwViewerOfferNotice(
                DwEligibleViewers(users = listOf(eligible("u-a")), truncated = false, search = "Aarav")
            )
        )

        assertEquals(
            "Too many to list — search by name or email.",
            dwViewerOfferNotice(
                DwEligibleViewers(users = listOf(eligible("u-a")), truncated = true, search = null)
            )
        )
        assertEquals(
            "Too many matches — narrow the search.",
            dwViewerOfferNotice(
                DwEligibleViewers(users = listOf(eligible("u-a")), truncated = true, search = "a")
            )
        )
        assertEquals(
            "No eligible account matches that search.",
            dwViewerOfferNotice(
                DwEligibleViewers(users = emptyList(), truncated = false, search = "Meher")
            )
        )
    }

    @Test
    fun `an answer that is cut and empty does not tell an admin to narrow an empty list`() {
        val expected =
            "Some accounts couldn't be listed. Try again later."

        val searched = dwViewerOfferNotice(
            DwEligibleViewers(users = emptyList(), truncated = true, search = "Meher")
        )
        val unsearched = dwViewerOfferNotice(
            DwEligibleViewers(users = emptyList(), truncated = true, search = null)
        )

        assertEquals(expected, searched)
        assertEquals(expected, unsearched)
        assertFalse("narrowing an empty answer cannot help", searched!!.contains("narrow"))
        assertFalse("and neither can typing", unsearched!!.contains("search a name"))
    }

    @Test
    fun `a search the server would refuse never leaves the phone`() {
        assertNull(dwViewerSearchTerm(null))
        assertNull(dwViewerSearchTerm(""))
        assertNull(dwViewerSearchTerm("   "))
        assertNull(dwViewerSearchTerm(" "))
        assertNull(dwViewerSearchTerm("  "))

        assertEquals("Nabakalebara8a", dwViewerSearchTerm("  Nabakalebara8a  "))

        val pasted = "a".repeat(400)
        assertEquals(DW_VIEWER_SEARCH_MAX, dwViewerSearchTerm(pasted)!!.length)
        val emoji = "x".repeat(DW_VIEWER_SEARCH_MAX - 1) + "🙂"
        val clamped = dwViewerSearchTerm(emoji)!!
        assertEquals(DW_VIEWER_SEARCH_MAX - 1, clamped.length)
        assertFalse("a lone high surrogate is not a term", clamped.last().isHighSurrogate())
    }

    @Test
    fun `a colleague ticked under one search is still there after the next one`() {
        val answer = listOf(eligible("u-second", name = "Second Surname"))
        val ticked = eligible("u-first", name = "Unrelated Designer Nabakalebara8a886916")

        val choices = dwViewerChoices(
            eligible = answer,
            viewers = emptyList(),
            creatorId = creator,
            readerId = reader,
            eligibleListComplete = false,
            retained = listOf(ticked),
        )

        assertEquals(listOf("u-second", "u-first"), choices.map { it.userId })
        assertFalse(
            "a colleague narrowed out by a search term has not been suspended from the roster",
            choices.first { it.userId == "u-first" }.grantedButIneligible
        )
    }

    @Test
    fun `a list that was searched or cut does not call the whole team suspended`() {
        val team = listOf(granted("u-a"), granted("u-b"))
        listOf(
            DwEligibleViewers(users = emptyList(), truncated = true, search = null),
            DwEligibleViewers(users = emptyList(), truncated = false, search = "nabakalebara"),
            DwEligibleViewers(users = emptyList(), truncated = true, search = "sync"),
        ).forEach { answer ->
            assertFalse("$answer is not the whole eligible set", answer.complete)
            val choices = dwViewerChoices(
                eligible = answer.users,
                viewers = team,
                creatorId = creator,
                readerId = reader,
                eligibleListComplete = answer.complete,
            )
            assertEquals(listOf("u-a", "u-b"), choices.map { it.userId })
            assertTrue(choices.none { it.grantedButIneligible })
        }

        val known = DwEligibleViewers(users = emptyList(), truncated = false, search = null)
        assertTrue(known.complete)
        assertTrue(
            dwViewerChoices(known.users, team, creator, reader, known.complete).all { it.grantedButIneligible }
        )
    }

    @Test
    fun `adopting the server's answer holds the creator out of the editable set`() {
        val selection = DwViewerSelection.adopt(
            rows = listOf(granted(creator), granted("u-a")),
            creatorId = creator,
        )

        assertEquals(setOf("u-a"), selection.baseline)
        assertEquals(setOf("u-a"), selection.selected)
        assertFalse("the creator's own row must never read as an unsaved change", selection.dirty)
        assertTrue(selection.creatorHasRow)
    }

    @Test
    fun `a creator row the server reported is re-attached to the payload`() {
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted(creator), granted("u-a")), creatorId = creator)
            .withSelection(setOf("u-a", "u-b"))

        assertEquals(setOf("u-a", "u-b", creator), selection.payload().toSet())
    }

    @Test
    fun `no creator row is invented when the server never reported one`() {
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted("u-a")), creatorId = creator)
            .withSelection(setOf("u-a"))

        assertEquals(listOf("u-a"), selection.payload())
    }

    @Test
    fun `ticking the creator in the picker cannot put them in the payload`() {
        val selection = DwViewerSelection
            .adopt(rows = emptyList(), creatorId = creator)
            .withSelection(setOf(creator, "u-a", ""))

        assertEquals(listOf("u-a"), selection.payload())
    }

    @Test
    fun `unticking somebody is what removes them, and it is counted before it is sent`() {
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted("u-a"), granted("u-b")), creatorId = creator)
            .withSelection(setOf("u-b", "u-c"))

        assertTrue(selection.dirty)
        assertEquals(setOf("u-c"), selection.added)
        assertEquals(setOf("u-a"), selection.removed)
        assertEquals(2, selection.resultingCount)
        assertEquals(setOf("u-b", "u-c"), selection.payload().toSet())
    }

    @Test
    fun `adding one colleague does not revoke the suspended one`() {
        val viewers = listOf(granted("u-suspended"))
        val choices = dwViewerChoices(listOf(eligible("u-new")), viewers, creator, reader, true)
        var selection = DwViewerSelection.adopt(viewers, creator)

        selection = selection.withSelection(selection.selected + "u-new")

        assertEquals(setOf("u-suspended", "u-new"), selection.payload().toSet())
        assertTrue(selection.removed.isEmpty())
        assertTrue(choices.any { it.userId == "u-suspended" })
    }

    @Test
    fun `discarding returns to what the repository holds`() {
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted("u-a")), creatorId = creator)
            .withSelection(emptySet())

        assertTrue(selection.dirty)
        assertFalse(selection.discard().dirty)
        assertEquals(setOf("u-a"), selection.discard().selected)
    }

    @Test
    fun `the answer becomes the baseline, never the payload that was sent`() {
        val afterSave = DwViewerSelection.adopt(
            rows = listOf(granted("u-a"), granted("u-other")),
            creatorId = creator,
        )

        assertEquals(setOf("u-a", "u-other"), afterSave.baseline)
        assertFalse(afterSave.dirty)
    }

    @Test
    fun `the server's ceiling is mirrored so the refusal is about people`() {
        val selection = DwViewerSelection()
            .withSelection((1..DW_VIEWER_LIMIT).map { "u-$it" }.toSet())
        assertFalse(selection.overLimit)

        assertTrue(selection.withSelection((0..DW_VIEWER_LIMIT).map { "u-$it" }.toSet()).overLimit)
    }

    @Test
    fun `a name is never a raw id`() {
        assertEquals("Aarav Sharma", dwPersonLabel("Aarav Sharma", "a@example.org"))
        assertEquals("a@example.org", dwPersonLabel("  ", "a@example.org"))
        assertEquals("Unknown user", dwPersonLabel(null, null))
    }

    @Test
    fun `a name that is only a no-break space falls through to the email, as it does in the browser`() {
        val nbsp = "\u00A0"
        val narrow = "\u202F"
        val figure = "\u2007"
        val ideographic = "\u3000"

        assertEquals("a@example.org", dwPersonLabel(nbsp, "a@example.org"))
        assertEquals("a@example.org", dwPersonLabel(narrow + figure, "a@example.org"))
        assertEquals("Meera Nair", dwPersonLabel(nbsp + "Meera Nair" + nbsp, "a@example.org"))
        assertEquals("Unknown user", dwPersonLabel(nbsp, ideographic))
    }

    @Test
    fun `a missing route is only ever read off the id-less call`() {
        assertTrue(dwViewerAdministrationMissing(404))
        assertFalse(dwViewerAdministrationMissing(403))
        assertFalse(dwViewerAdministrationMissing(null))
    }

    @Test
    fun `a 403 carries the server's own words and then says who can do this`() {
        val message =
            dwViewerFailureMessage(403, "Admin access required", DwViewerAttempt.SAVE, readerIsAdmin = false)

        assertTrue(message, message.startsWith("Admin access required. Only admins can change access"))
        assertTrue(
            "the refusal has to name who may act, or the designer it turns away has no next step",
            message.contains("Only admins can change access")
        )
        assertTrue(message.contains("Nothing was changed."))
    }

    @Test
    fun `an admin refused for the post they hold is told the server's reason and nothing else`() {
        val holder = "You are this workshop's inspector, so you can read it but not change it: whoever " +
            "inspects or supervises a workshop does not write it. Ask whoever made the appointment to " +
            "take you off that post if you need to work on it."

        val message = dwViewerFailureMessage(403, holder, DwViewerAttempt.SAVE, readerIsAdmin = true)

        assertEquals("$holder Nothing was changed.", message)
        assertFalse(message.contains("Only admins can change access"))

        assertEquals(
            "This was not allowed. Nothing was changed.",
            dwViewerFailureMessage(403, null, DwViewerAttempt.SAVE, readerIsAdmin = true)
        )
    }

    @Test
    fun `a refusal is never reported as being offline`() {
        listOf(403, 404, 422).forEach { status ->
            val message = dwViewerFailureMessage(status, "no", DwViewerAttempt.SAVE, readerIsAdmin = true)
            assertFalse(
                "HTTP $status must not send an admin looking for signal",
                message.contains("Could not connect")
            )
        }
    }

    @Test
    fun `a 5xx is not a connection problem and does not claim a save was harmless`() {
        val read = dwViewerFailureMessage(503, null, DwViewerAttempt.READ, readerIsAdmin = true)
        assertTrue(read.contains("not a connection problem"))
        assertTrue(read.contains("Nothing was changed"))

        val save = dwViewerFailureMessage(503, null, DwViewerAttempt.SAVE, readerIsAdmin = true)
        assertFalse(save.contains("Nothing was changed"))
        assertTrue(save.contains("may have been saved"))
    }

    @Test
    fun `no answer at all says the app cannot do this offline, and does not guess at a save`() {
        val read = dwViewerFailureMessage(null, null, DwViewerAttempt.READ, readerIsAdmin = true)
        assertTrue(read.contains("Could not connect"))
        assertTrue(read.contains("needs a connection"))
        assertTrue(read.contains("Nothing has been changed."))

        val save = dwViewerFailureMessage(null, null, DwViewerAttempt.SAVE, readerIsAdmin = true)
        assertFalse(save.contains("Nothing has been changed."))
        assertTrue(save.contains("may already have been saved"))
    }

    @Test
    fun `a 422 is the server's own sentence, which already names the account`() {
        val detail = "Meera Nair (meera@example.org) is not on the ACTIVE designer roster, so they " +
            "cannot sign in at all. Restore their roster entry first; a viewer row on its own would " +
            "leave this screen saying they have access while they are shown a refusal. Nothing was changed."

        val message = dwViewerFailureMessage(422, detail, DwViewerAttempt.SAVE, readerIsAdmin = true)

        assertTrue("the server's text is the only one that knows WHICH account", message.contains(detail))
    }
}

private const val LIVE_CAPPED_PAGE =
    """{"users":[{"id":"cmsi52v3f0002zl5vhyrnf236","name":"Admin On The Roster",""" +
        """"email":"roster-adminrostered-0723e25f@example.org","role":"ADMIN"},""" +
        """{"id":"cmsi4tosp0002tah4y81t20xi","name":"Admin On The Roster",""" +
        """"email":"roster-adminRostered-e8bc3cf0@example.org","role":"ADMIN"}],"truncated":true}"""

private const val LIVE_SEARCH_HIT =
    """{"users":[{"id":"cmsqk1ma9000310owey7it64u",""" +
        """"name":"Unrelated Designer Nabakalebara8a886916",""" +
        """"email":"dwviewer-outsider-09404670@example.org","role":"DESIGNER"}],"truncated":false}"""
