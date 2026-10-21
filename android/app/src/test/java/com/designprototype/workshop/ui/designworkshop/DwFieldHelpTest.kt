package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.FieldDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold-for-help (owner, 2026-10-21): the timing and the card's placement, without a device. */
class DwFieldHelpTest {

    @Test
    fun `the hold fires just before the platform long press and never under 300 ms`() {
        assertEquals(320L, DwFieldDetails.holdMillis(400L))
        assertEquals(420L, DwFieldDetails.holdMillis(500L))
        assertEquals(300L, DwFieldDetails.holdMillis(200L))
        assertTrue(DwFieldDetails.holdMillis(400L) < 400L)
    }

    @Test
    fun `only a field with details gets the gesture`() {
        assertTrue(DwFieldDetails.has(FieldDto(key = "a", label = "A", details = "More")))
        assertFalse(DwFieldDetails.has(FieldDto(key = "b", label = "B", details = " ")))
        assertFalse(DwFieldDetails.has(FieldDto(key = "c", label = "C")))
    }

    private fun place(top: Int, bottom: Int, cardHeight: Int, left: Int = 16, cardWidth: Int = 300) =
        DwFieldDetails.cardOffset(
            anchorLeft = left, anchorTop = top, anchorBottom = bottom,
            windowWidth = 360, windowHeight = 800,
            cardWidth = cardWidth, cardHeight = cardHeight, gap = 6,
        )

    @Test
    fun `the card sits under the field when it fits`() {
        assertEquals(16 to 206, place(top = 100, bottom = 200, cardHeight = 150))
    }

    @Test
    fun `the card goes over the field when there is no room under it`() {
        assertEquals(16 to 444, place(top = 600, bottom = 700, cardHeight = 150))
    }

    @Test
    fun `a field taller than the room either side pins the card to the bottom of the window`() {
        assertEquals(16 to 600, place(top = 50, bottom = 790, cardHeight = 200))
    }

    @Test
    fun `the card stays inside the window horizontally`() {
        assertEquals(60, place(top = 100, bottom = 200, cardHeight = 100, left = 200).first)
        assertEquals(0, place(top = 100, bottom = 200, cardHeight = 100, left = -20).first)
        assertEquals(0, place(top = 100, bottom = 200, cardHeight = 100, cardWidth = 500).first)
    }
}
