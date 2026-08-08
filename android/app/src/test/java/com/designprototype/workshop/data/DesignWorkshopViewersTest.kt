package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Putting a second designer on a workshop, and the three ways this screen could take one OFF by
 * accident.
 *
 * ── WHY EVERY ONE OF THESE IS A TEST AND NOT A CODE REVIEW ───────────────────────────────────────
 *
 * `PUT /design-workshops/{id}/viewers` REPLACES the whole set. There is no add route and no remove
 * route, so every id absent from the payload is a row the server DELETES — silently, because a
 * deletion is the intended meaning of the call. That single fact turns three ordinary-looking client
 * bugs into revocations nobody sees:
 *
 *  1. building the picker from the eligible list alone drops a designer whose roster row was
 *     suspended since their grant, so adding one colleague quietly removes another;
 *  2. sending only what was ticked drops the creator's own row when the server reported one;
 *  3. adopting the payload as the new baseline instead of the ANSWER shows a membership nobody has,
 *     the moment two admins edit the same workshop.
 *
 * None of the three shows up on screen: the list redraws, it looks right, and a colleague finds out
 * a week later when a workshop stops opening. The pure half of the screen is separated from Compose
 * precisely so all three can be asserted here in milliseconds.
 *
 * ── AND THE MESSAGES, BECAUSE THIS APP HAS SHIPPED THE OTHER FAILURE TWICE ───────────────────────
 *
 * A permission refusal reaching a person as a network message, or the reverse, is in
 * SESSION_HANDOVER.md twice. `dwViewerFailureMessage` is where that decision is made for this
 * feature, and the sentences are asserted rather than read — including the one claim that must never
 * be printed when it cannot be known: that a save which never got an answer changed nothing.
 */
class DesignWorkshopViewersTest {

    private val creator = "u-creator"

    private fun eligible(id: String, name: String = id, role: String = "DESIGNER") =
        DwEligibleViewerDto(id = id, name = name, email = "$id@example.org", role = role)

    private fun granted(id: String, name: String = id, role: String = "DESIGNER") =
        DwViewerDto(userId = id, name = name, email = "$id@example.org", role = role)

    // ── The picker's options ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the creator is offered by neither list`() {
        // Their access comes from `createdById` and the server drops them from any payload naming
        // them (`_deduplicate`), so an option for them would be a control that cannot do what it
        // appears to. The web panel calls this out as its first design decision.
        val choices = dwViewerChoices(
            eligible = listOf(eligible(creator), eligible("u-b")),
            viewers = listOf(granted(creator), granted("u-b")),
            creatorId = creator,
        )

        assertEquals(listOf("u-b"), choices.map { it.userId })
    }

    @Test
    fun `a viewer the server no longer offers is still offered here, ticked and marked`() {
        // THE LOAD-BEARING ONE. `eligible_viewers` excludes a DESIGNER whose DesignerRoster row is
        // suspended — deliberately, so an admin cannot grant access the next sign-in refuses — but
        // their EXISTING row stands. Leaving them out of the options would mean the next Save sends
        // a set without them and revokes a colleague as a side effect of adding somebody unrelated.
        val choices = dwViewerChoices(
            eligible = listOf(eligible("u-active")),
            viewers = listOf(granted("u-suspended")),
            creatorId = creator,
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
        // THE TRAP BEHIND THE EARLY RETURN IN WorkshopViewersScreen, written down where the next
        // person to touch that load will see the consequence rather than the rule.
        //
        // This function cannot tell "the server offered a list without them" from "no list arrived":
        // both are an empty `eligible`, and both mark every current viewer "has access, no longer
        // eligible". The first is the truth the mark exists for; the second is a screen telling an
        // administrator that every designer on the workshop has been suspended from the roster,
        // with nobody available to add. It cannot be fixed here — an empty eligible list IS a legal
        // answer, from a repository whose designers are all suspended — so it is fixed by the
        // caller refusing to draw the picker at all when the call failed with anything but the 404
        // that means "this deployment predates the feature". Delete that early return and this test
        // still passes; that is exactly why the consequence is spelled out here.
        val choices = dwViewerChoices(
            eligible = emptyList(),
            viewers = listOf(granted("u-a"), granted("u-b")),
            creatorId = creator,
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
        )

        assertEquals(listOf("u-a", "u-b"), choices.map { it.userId })
        // Marked from the ELIGIBLE side, because that is what they are: still offered.
        assertFalse(choices.first { it.userId == "u-b" }.grantedButIneligible)
    }

    @Test
    fun `a blank id is not an option`() {
        // A blank id cannot be granted, cannot be de-duplicated against, and would collide with
        // every other blank one into a single phantom row — the same rule the list walk applies.
        val choices = dwViewerChoices(
            eligible = listOf(eligible(""), eligible("u-a")),
            viewers = listOf(granted("")),
            creatorId = creator,
        )

        assertEquals(listOf("u-a"), choices.map { it.userId })
    }

    @Test
    fun `the order is the server's, not this client's`() {
        // `eligible_viewers` orders by name in Postgres's collation. Re-sorting here with Kotlin's
        // String comparator would order by UTF-16 code unit and disagree with the browser on exactly
        // the names this repository is full of.
        val choices = dwViewerChoices(
            eligible = listOf(eligible("u-z", name = "Zoya"), eligible("u-a", name = "Aarav")),
            viewers = emptyList(),
            creatorId = creator,
        )

        assertEquals(listOf("u-z", "u-a"), choices.map { it.userId })
    }

    // ── The pending set ──────────────────────────────────────────────────────────────────────────

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
        // The PUT replaces the whole set, so dropping a row the server itself told us about is the
        // one edit this screen may not make — even though the server would tolerate it, because a
        // client that silently deletes rows it did not understand is the shape of the next bug.
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted(creator), granted("u-a")), creatorId = creator)
            .withSelection(setOf("u-a", "u-b"))

        assertEquals(setOf("u-a", "u-b", creator), selection.payload().toSet())
    }

    @Test
    fun `no creator row is invented when the server never reported one`() {
        // The ordinary case: the creator holds the workshop through `createdById` and has no viewer
        // row at all. Adding one would write a second, redundant source of truth for access they
        // already hold — and one an admin could "remove" from this screen with nothing changing.
        val selection = DwViewerSelection
            .adopt(rows = listOf(granted("u-a")), creatorId = creator)
            .withSelection(setOf("u-a"))

        assertEquals(listOf("u-a"), selection.payload())
    }

    @Test
    fun `ticking the creator in the picker cannot put them in the payload`() {
        // Defence in depth against a future option list that forgets to exclude them. Harmless on
        // the server (`_deduplicate` drops the creator before validation, so a suspended creator
        // cannot even make the save 422) and harmless here.
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
        // Sent as the WHOLE set. A payload of just "u-c" — the thing that was ticked — would revoke
        // both of the others.
        assertEquals(setOf("u-b", "u-c"), selection.payload().toSet())
    }

    @Test
    fun `adding one colleague does not revoke the suspended one`() {
        // The end-to-end shape of defect (1), driven through the two functions that prevent it: the
        // options list keeps the ineligible holder, `adopt` ticks them, and the payload therefore
        // still names them after the admin adds somebody else.
        val viewers = listOf(granted("u-suspended"))
        val choices = dwViewerChoices(listOf(eligible("u-new")), viewers, creator)
        var selection = DwViewerSelection.adopt(viewers, creator)

        // Exactly what the multi-select hands back after one tap: every currently-ticked option plus
        // the new one.
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
        // Two admins on one workshop. This device sent {u-a}; the server answers {u-a, u-other},
        // because somebody else added u-other while this screen was open. Adopting our own request
        // would show a membership nobody has — and the next Save would revoke u-other.
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
        // THE WEB IS THE SOURCE HERE — `personLabel` in DesignWorkshopViewersPanel.tsx is
        // `name?.trim() || email?.trim() || "Unknown user"`, and JavaScript's trim strips U+00A0
        // and U+202F while Kotlin's `trim()`/`isBlank()` (Char.isWhitespace) deliberately do not.
        // A directory row pasted out of a spreadsheet or a ministry PDF carries them, so under the
        // Kotlin spelling the browser named the designer and the handset drew an option with an
        // invisible label and no address — on the screen where picking the wrong row grants a
        // stranger a fortnight of somebody's fieldwork. No test reports a divergence like this; it
        // is only ever seen by the admin holding the phone.
        // Spelled as escapes and never as literal characters: a source file cannot show the
        // difference between these and an ordinary space, and an invisible character in a
        // whitespace test is one nobody can review.
        val nbsp = "\u00A0"          // what a spreadsheet paste leaves between words
        val narrow = "\u202F"        // and what a PDF paste leaves
        val figure = "\u2007"
        val ideographic = "\u3000"

        assertEquals("a@example.org", dwPersonLabel(nbsp, "a@example.org"))
        assertEquals("a@example.org", dwPersonLabel(narrow + figure, "a@example.org"))
        assertEquals("Meera Nair", dwPersonLabel(nbsp + "Meera Nair" + nbsp, "a@example.org"))
        // And with nothing else to fall back to, the neutral word rather than an invisible one.
        assertEquals("Unknown user", dwPersonLabel(nbsp, ideographic))
    }

    // ── Telling the failures apart ───────────────────────────────────────────────────────────────

    @Test
    fun `a missing route is only ever read off the id-less call`() {
        assertTrue(dwViewerAdministrationMissing(404))
        assertFalse(dwViewerAdministrationMissing(403))
        assertFalse(dwViewerAdministrationMissing(null))
    }

    @Test
    fun `a 403 carries the server's own words and then says who can do this`() {
        val message = dwViewerFailureMessage(403, "Admin access required", DwViewerAttempt.SAVE)

        // `require_admin` raises its detail with no final stop; every message in
        // `services/design_workshop_viewers` ends in one. Both have to read as prose.
        assertTrue(message, message.startsWith("Admin access required. Deciding who may open"))
        assertTrue(
            "the refusal has to name who may act, or the designer it turns away has no next step",
            message.contains("admins and the master admin only")
        )
        // The one thing a 403 IS safe to promise: the server refused before writing anything.
        assertTrue(message.contains("Nothing was changed."))
    }

    @Test
    fun `a refusal is never reported as being offline`() {
        listOf(403, 404, 422).forEach { status ->
            val message = dwViewerFailureMessage(status, "no", DwViewerAttempt.SAVE)
            assertFalse(
                "HTTP $status must not send an admin looking for signal",
                message.contains("could not reach")
            )
        }
    }

    @Test
    fun `a 5xx is not a connection problem and does not claim a save was harmless`() {
        val read = dwViewerFailureMessage(503, null, DwViewerAttempt.READ)
        assertTrue(read.contains("not a connection problem"))
        assertTrue(read.contains("Nothing was changed"))

        // `replace_viewers` issues its delete_many and its create_many as two statements, so a fault
        // between them leaves the removals applied and the additions not. Promising an admin their
        // revocation did not happen when it did is the worst sentence this screen could print.
        val save = dwViewerFailureMessage(503, null, DwViewerAttempt.SAVE)
        assertFalse(save.contains("Nothing was changed"))
        assertTrue(save.contains("may have landed"))
    }

    @Test
    fun `no answer at all says the app cannot do this offline, and does not guess at a save`() {
        val read = dwViewerFailureMessage(null, null, DwViewerAttempt.READ)
        assertTrue(read.contains("could not reach the repository"))
        // The one capability in this app that a courtyard defeats, said as such rather than as a
        // generic failure — every other design-workshop screen works with no signal at all.
        assertTrue(read.contains("cannot be done offline"))
        assertTrue(read.contains("Nothing has been changed."))

        val save = dwViewerFailureMessage(null, null, DwViewerAttempt.SAVE)
        assertFalse(save.contains("Nothing has been changed."))
        assertTrue(save.contains("may still have landed"))
    }

    @Test
    fun `a 422 is the server's own sentence, which already names the account`() {
        val detail = "Meera Nair (meera@example.org) is not on the ACTIVE designer roster, so they " +
            "cannot sign in at all. Restore their roster entry first; a viewer row on its own would " +
            "leave this screen saying they have access while they are shown a refusal. Nothing was changed."

        val message = dwViewerFailureMessage(422, detail, DwViewerAttempt.SAVE)

        assertTrue("the server's text is the only one that knows WHICH account", message.contains(detail))
    }
}
