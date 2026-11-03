package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCascadeClearTest {

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
        assertTrue(
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = true, selectedId = "prc_1")
        )
    }

    @Test
    fun `a field with no cascade is never cleared by anything above it`() {
        assertFalse(
            dwCascadeClearsChild(refFilterBy = "", parentMoved = true, selectedId = "art_1")
        )
    }

    @Test
    fun `an unchanged parent leaves a stored pair alone`() {
        assertFalse(
            "opening a form must not clear the link it was opened to show",
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = false, selectedId = "prc_1")
        )
    }

    @Test
    fun `a parent moving over an empty child changes nothing and says nothing`() {
        assertFalse(
            dwCascadeClearsChild(refFilterBy = "productRef", parentMoved = true, selectedId = "")
        )
    }

    @Test
    fun `the message names the control that moved`() {
        val message = dwCascadeClearedMessage("Documented product")
        assertTrue(message, message.startsWith("The documented product on this row changed"))
    }

    @Test
    fun `the message still reads when the parent has no label`() {
        val message = dwCascadeClearedMessage("")
        assertTrue(message, message.startsWith("The record this list narrows to changed"))
    }

    @Test
    fun `the message says the boxes the old choice filled in will be cleared on save`() {
        val message = dwCascadeClearedMessage("Documented product")
        assertTrue(message, message.contains("cleared when the row is saved"))
    }

    @Test
    fun `the two cascade sentences are not the same sentence`() {
        val cleared = dwCascadeClearedMessage("Documented product")
        val moved = dwScanCascadeMovedMessage("Documented product")
        assertEquals(false, cleared == moved)
        assertTrue(moved, moved.contains("Nothing on this row has been changed"))
        assertFalse(cleared, cleared.contains("Nothing on this row has been changed"))
    }
}
