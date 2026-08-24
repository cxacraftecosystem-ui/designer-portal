package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHEN THE PARENT MOVES, THE CHILD GOES WITH IT — the handset's half of the cascade, which was missing.
 *
 * ── WHAT WAS WRONG ───────────────────────────────────────────────────────────────────────────────
 *
 * `DwReferenceSelectField` honoured the NARROWING from the day it was written: it withholds the
 * dropdown while the parent is blank, and it sends `filterBy` so the server cannot offer another
 * product's process. It had no counterpart to the browser's CLEAR. So: pick product A, pick process P
 * of A, change the product to B — and `processRef` still held P. A pair `reference_options` would never
 * have offered, that `coerce_value` does not refuse (it checks type and length, never coherence), with
 * `hydrationPatch` rewriting `documentedFor` to B while `name`, `description` and the rest still came
 * from A's process. Nothing on screen said so.
 *
 * It was survivable while the only cascade was `existingProduct.artisanRef -> productRef` on a
 * collection row, where the mismatch was one product name in one cell. The same rule now governs
 * stage 5's substantive narrative and the stored `processRef` join key.
 *
 * ── WHY THIS IS A UNIT TEST ──────────────────────────────────────────────────────────────────────
 *
 * The same reason `DwReferenceScanTest`'s header gives: the decision is made before a pixel is drawn,
 * and this module's unit tests have no Compose runtime. So the rule is [dwCascadeClearsChild] — the
 * declaration, whether the parent moved, and what the child holds — and the `LaunchedEffect` observes
 * the parent, calls it, and acts. Everything worth pinning is in the function, including the two false
 * positives that would clear a link the designer made a fortnight ago.
 */
class DwCascadeClearTest {

    // ── The case the feature exists for ──────────────────────────────────────────────────────────

    @Test
    fun `a chosen child is cleared when its parent changes`() {
        assertTrue(
            "product A -> process P -> product B must drop P: it is a process of A, and nothing " +
                "downstream can tell that a stored pair is one the picker would never have offered",
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = true, selectedId = "prc_1")
        )
    }

    @Test
    fun `clearing the parent clears the child too`() {
        // The parent moving to NOTHING is still the parent moving. The field is about to withhold its
        // list entirely (`needsParent`), so leaving an id behind would leave a link the designer
        // cannot see, cannot change and cannot clear from a control that is no longer offered.
        assertTrue(
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = true, selectedId = "prc_1")
        )
    }

    // ── The three shapes that must NOT fire ──────────────────────────────────────────────────────

    @Test
    fun `a field with no cascade is never cleared by anything above it`() {
        // `participant.artisanRef` and the rest declare no `refFilterBy`. A rule that fired on them
        // would clear a link because some unrelated box on the row changed.
        assertFalse(
            dwCascadeClearsChild(refFilterBy = "", parentMoved = true, selectedId = "art_1")
        )
    }

    @Test
    fun `an unchanged parent leaves a stored pair alone`() {
        // THE FALSE POSITIVE THAT MATTERS MOST. A saved row arrives with the product and the process
        // both stored and agreeing, and so does a draft rehydrating from disk and a stage being
        // re-read. Every one of those composes this field with a parent it has not seen before — which
        // is why the caller compares against the parent it was LAST COMPOSED under and not against a
        // blank, and why `parentMoved` is false on all of them.
        assertFalse(
            "opening a form must not clear the link it was opened to show",
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = false, selectedId = "prc_1")
        )
    }

    @Test
    fun `a parent moving over an empty child changes nothing and says nothing`() {
        // Choosing the product on a fresh row is the ordinary first step of the cascade. There is no
        // stale choice to drop, so there is no message either: the field already tells the designer to
        // choose the parent first, and a second sentence about a clear that did not happen reads as an
        // error report on a form being filled in correctly.
        assertFalse(
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = true, selectedId = "")
        )
    }

    // ── The sentence ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the message names the control that moved`() {
        // Named, not hinted, for the reason the `needsParent` line gives: a sentence about "the record
        // this list depends on" with no record named is one a designer reads three times looking for
        // the control it means.
        val message = dwCascadeClearedMessage("Documented product")
        assertTrue(message, message.startsWith("The documented product on this row changed"))
    }

    @Test
    fun `the message still reads when the parent has no label`() {
        // `cascadeLabel` comes from `parentField?.label`, and a client one release behind the registry
        // can hold a row whose parent field it cannot describe. The sentence must not become "The  on
        // this row changed".
        val message = dwCascadeClearedMessage("")
        assertTrue(message, message.startsWith("The record this list narrows to changed"))
    }

    @Test
    fun `the message says the boxes the old choice filled in will be cleared on save`() {
        // THE CLAUSE A SHORTER MESSAGE WOULD DROP, and it is load-bearing rather than polite. The
        // clear takes the ID and leaves the values; the SAVE takes the values
        // (`design_workshops._clear_cascade_orphans`). Between the two the row shows a process's name
        // with no process linked, and a designer who reads that as a bug retypes the boxes by hand —
        // which defeats the fix, because a TYPED value is not one hydration may overwrite.
        val message = dwCascadeClearedMessage("Documented product")
        assertTrue(message, message.contains("cleared when the row is saved"))
    }

    @Test
    fun `the two cascade sentences are not the same sentence`() {
        // [dwCascadeClearedMessage] reports a change that HAS been made to the row;
        // [dwScanCascadeMovedMessage] reports an answer DROPPED with the row left untouched. They are
        // the opposite promise, they arrive in the same live region, and the second says "Nothing on
        // this row has been changed" — which would be a lie if the first ever reused it.
        val cleared = dwCascadeClearedMessage("Documented product")
        val moved = dwScanCascadeMovedMessage("Documented product")
        assertEquals(false, cleared == moved)
        assertTrue(moved, moved.contains("Nothing on this row has been changed"))
        assertFalse(cleared, cleared.contains("Nothing on this row has been changed"))
    }
}
