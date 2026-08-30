package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PRODUCER HALF OF THE UNFILE SENTINEL — the rule that decides which of the two absences an
 * empty workshop box is, and therefore whether a link is CLEARED or LEFT ALONE.
 *
 * -- WHY THIS IS A SEPARATE TEST FROM `OutboxDanglingReferenceTest` ----------------------------
 *
 * That one pins what the QUEUE does once the reason is written down: `UNFILED_BY_CHOICE` reaches the
 * wire as an explicit null and `UNFILED_NO_OPTIONS` sends nothing. It never asks where the value
 * came from — every one of its entries is built with the map already filled in. So the whole
 * mechanism could be correct end to end and still be fed the wrong answer by the two pickers, which
 * is the only half a person can actually see.
 *
 * -- THE ARM THAT MATTERS, AND THE OBVIOUS IMPLEMENTATION THAT GETS IT WRONG -------------------
 *
 * The tempting rule is "if the list is empty there was nothing to choose". It is wrong, and wrong in
 * the direction that loses a designer's work rather than the direction that is merely quiet.
 *
 * A record filed under a workshop this device cannot list still draws an off-page row
 * (`offPageWorkshopRow`), so the control is ENABLED and the "None" row is reachable while
 * `workshops` is empty — a designer on a dead connection opening a record filed by mistake, tapping
 * None, and saving. Under the tempting rule that deliberate clearance is filed as "there was nothing
 * to choose", the key is omitted, the correction returns 200, and the workshop is still there. That
 * is the exact defect `DesignWorkshopPicker`'s KDoc has described since the picker was written,
 * surviving the fix that was supposed to close it.
 *
 * The baseline is what tells them apart: it is what the box held when the form was BUILT, it is
 * never moved by a tap, and a blank box over a non-blank baseline can only have been produced by a
 * person.
 */
class OutboxUnfiledSentinelTest {

    // -- The four states of one box ---------------------------------------------------------------

    @Test
    fun `a box with something in it has no absence to explain`() {
        // Nothing is reported whatever the rest looks like: the id itself is what goes on the wire,
        // and a reason beside it would be a second answer to a question already answered.
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "dw_9", hadOptions = true))
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "", hadOptions = false))
        assertNull(unfiledLinkReason(selectedId = "dw_9", baselineId = "dw_1", hadOptions = false))
    }

    @Test
    fun `a person who empties a filled box is heard even when the list behind it is empty`() {
        // THE LOAD-BEARING CASE. Edit of a record filed under `dw_9`, no signal, so `workshops` is
        // empty and the only row on offer is the off-page one — which is exactly why the control is
        // still open and the None row still reachable. Deciding this by the list would read the tap
        // as silence and leave the link in the database under a 200.
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = false),
        )
        // And the same tap with the list present, which must not be a different answer.
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = true),
        )
    }

    @Test
    fun `a box that was never filled and had a list to offer is a decision`() {
        // A create, or an edit of an already-unfiled record: the designer was shown the register and
        // left the box alone. The column is already empty on the server, so the null is a no-op —
        // what this arm actually decides is that the drain says NOTHING about it, because there is
        // nothing to warn a designer about.
        assertEquals(
            UNFILED_BY_CHOICE,
            unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = true),
        )
    }

    @Test
    fun `a box that was never filled and had nothing to offer is not a decision`() {
        // The courtyard case. No signal, the access lists are never cached, so the picker was empty
        // and the designer made no choice at all. This is the one arm the drain has to speak about.
        assertEquals(
            UNFILED_NO_OPTIONS,
            unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false),
        )
    }

    // -- The two absences, carried through the queue to opposite ends -----------------------------

    @Test
    fun `only the chosen absence becomes a clearance on the replay`() {
        val chosen = pending(
            workshopUnfiledReasons(
                designWorkshop = unfiledLinkReason(selectedId = "", baselineId = "dw_9", hadOptions = false),
            )
        )
        val nothingOnOffer = pending(
            workshopUnfiledReasons(
                designWorkshop = unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false),
            )
        )

        // A cleared column is sent as an explicit null — `patchBodyWithClearances` reads this set.
        assertEquals(setOf("designWorkshopId"), chosen.clearedLinkKeys)
        assertEquals(emptySet<String>(), chosen.emptyPickerKeys)
        // An empty picker sends nothing for the column and is REPORTED when the record lands, which
        // is the opposite remedy for the opposite failure. R7 forbids collapsing the two.
        assertEquals(emptySet<String>(), nothingOnOffer.clearedLinkKeys)
        assertEquals(setOf("designWorkshopId"), nothingOnOffer.emptyPickerKeys)
    }

    // -- The column names, which nothing downstream would complain about --------------------------

    @Test
    fun `the two columns are spelled the way the server spells them`() {
        val both = workshopUnfiledReasons(
            designWorkshop = UNFILED_BY_CHOICE,
            workshop = UNFILED_BY_CHOICE,
        )
        assertEquals(WORKSHOP_LINK_KEYS, both.keys)
        // A MISTYPED COLUMN FAILS NOWHERE. It would ride in `unfiled`, survive into
        // `clearedLinkKeys`, be offered to `patchBodyWithClearances`, be found undeclared by the
        // request class, and be skipped in silence — the designer's clearance lost between two files
        // with no error anywhere on the path. So the names are asserted against the sets the wire
        // actually uses rather than retyped here.
        assertTrue(both.keys.all { it in REFERENCE_FIELD_NOUNS })
    }

    @Test
    fun `a form that mounts one picker cannot unfile the column it never showed anybody`() {
        // The design-workshop box was emptied; there was no field-workshop box on this form at all.
        // A map naming both columns would clear a link the designer was never offered a control for.
        val one = workshopUnfiledReasons(designWorkshop = UNFILED_BY_CHOICE)
        assertEquals(mapOf("designWorkshopId" to UNFILED_BY_CHOICE), one)
        assertEquals(setOf("designWorkshopId"), pending(one).clearedLinkKeys)

        // And a form that passes neither queues exactly as every build before the sentinel did: no
        // evidence, no clearance. This is why forgetting to thread it through loses a clearance
        // rather than inventing one.
        assertEquals(emptyMap<String, String>(), workshopUnfiledReasons())
        assertEquals(emptySet<String>(), pending(workshopUnfiledReasons()).clearedLinkKeys)
    }

    private fun pending(unfiled: Map<String, String>) = PendingEntry(
        id = "entry-1",
        type = "artisan",
        payloadJson = "{\"name\":\"Giriraj Prasad\"}",
        label = "Giriraj Prasad",
        createdAt = "2026-08-30T10:00:00Z",
        targetId = "ar_1",
        unfiled = unfiled,
    )
}
