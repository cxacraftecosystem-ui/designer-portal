package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.dwMoveTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwCollectionDragTest {

    @Test
    fun `a move means the same thing as it does on the ranking list, for every pair`() {
        for (size in 0..6) {
            val order = (0 until size).map { "row-$it" }
            for (from in -2..size + 1) {
                for (to in -2..size + 1) {
                    assertEquals(
                        "dwMovedTo and dwMoveTo disagree at size=$size from=$from to=$to — the " +
                            "arrows and the grip would then write different arrangements, and the " +
                            "one that reached the report would depend on which control was used",
                        dwMoveTo(order, from, to),
                        dwMovedTo(order, from, to),
                    )
                }
            }
        }
    }

    @Test
    fun `moving across the list carries the rows between along, rather than swapping two`() {
        val rows = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "d", "a", "e"), dwMovedTo(rows, 0, 3))
        assertNotEquals(listOf("d", "b", "c", "a", "e"), dwMovedTo(rows, 0, 3))
    }

    @Test
    fun `a move that changes nothing changes nothing`() {
        val rows = listOf("a", "b", "c")
        assertEquals(rows, dwMovedTo(rows, 1, 1))
        assertEquals(rows, dwMovedTo(rows, 0, -5))
        assertEquals(rows, dwMovedTo(rows, 2, 99))
        assertEquals(rows, dwMovedTo(rows, -1, 0))
        assertEquals(rows, dwMovedTo(rows, 3, 0))
        assertEquals(emptyList<String>(), dwMovedTo(emptyList<String>(), 0, 0))
    }

    private val heights = listOf(68f, 600f, 68f, 68f)
    private val gap = 8f

    private fun dragFrom(from: Int) = DwCollectionDrag(
        rowId = "row-$from",
        from = from,
        to = from,
        offset = 0f,
        snapshot = heights.indices.map { "row-$it" },
        heights = heights,
    )

    @Test
    fun `a collapsed row passes an expanded one only when their centres cross`() {
        val drag = dragFrom(0)
        assertEquals(0, drag.dwAdvancedBy(100f, gap).to)
        assertEquals(0, drag.dwAdvancedBy(341f, gap).to)
        assertEquals(1, drag.dwAdvancedBy(343f, gap).to)
        assertEquals(2, drag.dwAdvancedBy(700f, gap).to)
        assertEquals(3, drag.dwAdvancedBy(800f, gap).to)
    }

    @Test
    fun `dragging upward takes the target when the centre passes the other centre`() {
        val drag = dragFrom(3)
        assertEquals(3, drag.dwAdvancedBy(-75f, gap).to)
        assertEquals(2, drag.dwAdvancedBy(-100f, gap).to)
        assertEquals(1, drag.dwAdvancedBy(-450f, gap).to)
        assertEquals(0, drag.dwAdvancedBy(-800f, gap).to)
    }

    @Test
    fun `no travel can push the target outside the list`() {
        listOf(-100000f, -1f, 0f, 1f, 100000f).forEach { delta ->
            heights.indices.forEach { from ->
                val next = dragFrom(from).dwAdvancedBy(delta, gap)
                assertTrue(
                    "a drag of $delta from $from named index ${next.to}, which is not a row",
                    next.to in heights.indices,
                )
            }
        }
    }

    @Test
    fun `the offset is the sum of the deltas, not the last one`() {
        val moved = dragFrom(0)
            .dwAdvancedBy(100f, gap)
            .dwAdvancedBy(150f, gap)
            .dwAdvancedBy(100f, gap)
        assertEquals(350f, moved.offset, 0.001f)
        assertEquals(1, moved.to)
    }

    @Test
    fun `only the rows between where it came from and where it is going move`() {
        val drag = dragFrom(0).copy(to = 2, offset = 700f)
        assertEquals(700f, drag.dwShiftFor(0, gap), 0.001f)
        assertEquals(-76f, drag.dwShiftFor(1, gap), 0.001f)
        assertEquals(-76f, drag.dwShiftFor(2, gap), 0.001f)
        assertEquals(0f, drag.dwShiftFor(3, gap), 0.001f)
    }

    @Test
    fun `dragging upward pushes the displaced rows down`() {
        val drag = dragFrom(3).copy(to = 1, offset = -450f)
        assertEquals(0f, drag.dwShiftFor(0, gap), 0.001f)
        assertEquals(76f, drag.dwShiftFor(1, gap), 0.001f)
        assertEquals(76f, drag.dwShiftFor(2, gap), 0.001f)
        assertEquals(-450f, drag.dwShiftFor(3, gap), 0.001f)
    }

    @Test
    fun `with no drag in flight every row sits still`() {
        val none: DwCollectionDrag? = null
        (0..4).forEach { assertEquals(0f, none.dwShiftFor(it, gap), 0.001f) }
    }

    @Test
    fun `an unmeasured row shifts nothing and names no target`() {
        val unmeasured = DwCollectionDrag(
            rowId = "row-1",
            from = 1,
            to = 1,
            offset = 0f,
            snapshot = listOf("row-0", "row-1"),
            heights = emptyList(),
        )
        assertEquals(0f, unmeasured.dwShiftFor(0, gap), 0.001f)
        assertEquals(0f, unmeasured.dwShiftFor(1, gap), 0.001f)
        val advanced = unmeasured.dwAdvancedBy(50f, gap)
        assertEquals(50f, advanced.offset, 0.001f)
        assertEquals(1, advanced.to)
    }

    @Test
    fun `a move is announced with the row's name and its new place, counted from one`() {
        val said = dwRowMovedSentence("Teak batten 50x25", landed = 2, total = 9)
        assertEquals("Teak batten 50x25 moved to position 3 of 9.", said)
        assertTrue(said.contains("Teak batten 50x25"))
    }

    @Test
    fun `an abandoned gesture says so, names the row, and claims no position`() {
        val said = dwRowDragAbandonedSentence("Teak batten 50x25")
        assertTrue(said.contains("Teak batten 50x25"))
        assertTrue("an abandoned drag has to say it was not moved", said.contains("was not moved"))
        assertTrue("and has to say what to do next", said.contains("Try again"))
        assertFalse(
            "the abandoned sentence names a position — that is the confident wrong answer the " +
                "whole abandonment exists to avoid",
            said.contains("moved to position"),
        )
    }

    @Test
    fun `a move and a refused move do not sound alike`() {
        val moved = dwRowMovedSentence("Prototype 2", landed = 0, total = 4)
        val refused = dwRowDragAbandonedSentence("Prototype 2")
        assertNotEquals(moved, refused)
        assertTrue(moved.isNotBlank())
        assertTrue(refused.isNotBlank())
    }
}
