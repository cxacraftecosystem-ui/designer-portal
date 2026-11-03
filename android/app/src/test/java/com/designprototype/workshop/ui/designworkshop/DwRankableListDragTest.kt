package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwRankableListDragTest {

    private val heights = listOf(60f, 180f, 40f, 60f, 60f)
    private val gap = 12f

    private val tops = listOf(0f, 72f, 264f, 316f, 388f)

    private fun dragOf(from: Int) = DwDragState(
        key = "row$from",
        from = from,
        to = from,
        offset = 0f,
        travel = 0f,
        heading = 0,
        snapshot = List(heights.size) { "row$it" },
        heights = heights,
    )

    @Test
    fun `the derived tops are the layout as it stood at drag start`() {
        var running = 0f
        heights.forEachIndexed { index, height ->
            assertEquals("row $index's top", tops[index], running, 0.001f)
            running += height + gap
        }
    }

    @Test
    fun `the target is the furthest row whose centre the dragged centre has passed`() {
        assertEquals(0, dragOf(0).advancedBy(131f, gap).to)
        assertEquals(1, dragOf(0).advancedBy(133f, gap).to)
        assertEquals(2, dragOf(4).advancedBy(-135f, gap).to)
        assertEquals(1, dragOf(4).advancedBy(-257f, gap).to)
    }

    @Test
    fun `a gesture that has not moved has not moved anything`() {
        heights.indices.forEach { from ->
            assertEquals("row $from at rest", from, dragOf(from).advancedBy(0f, gap).to)
        }
    }

    @Test
    fun `A SCROLL AND A THUMB ARE THE SAME PIXELS - only the sum of the two decides the target`() {
        heights.indices.forEach { from ->
            listOf(-300f, -140f, -1f, 0f, 1f, 140f, 300f).forEach { total ->
                val whole = dragOf(from).advancedBy(total, gap)
                listOf(0f, total / 4f, total / 2f, total).forEach { thumb ->
                    val split = dragOf(from).advancedBy(thumb, gap).advancedBy(total - thumb, gap)
                    assertEquals(
                        "row $from: ${thumb}px of thumb then ${total - thumb}px of scroll",
                        whole.to,
                        split.to,
                    )
                    assertEquals("and the card has travelled the same distance", whole.offset, split.offset, 0.001f)
                }
            }
        }
    }

    @Test
    fun `a scroll does not invalidate the snapshot the gesture was measured against`() {
        val scrolledThere = dragOf(0)
            .advancedBy(50f, gap)
            .advancedBy(120f, gap)
            .advancedBy(120f, gap)
            .advancedBy(60f, gap)
        val draggedThere = dragOf(0).advancedBy(350f, gap)

        assertEquals("the same destination", draggedThere.to, scrolledThere.to)
        assertEquals("from the same heights", heights, scrolledThere.heights)
        assertEquals("and the same arrangement, untouched", draggedThere.snapshot, scrolledThere.snapshot)
    }

    @Test
    fun `the snapshot can outlive the list it was taken of, and answers rather than throws`() {
        val stale = dragOf(3).copy(from = heights.size)
        assertEquals(
            "a `from` the snapshot no longer holds leaves the target exactly where it was",
            stale.to,
            stale.advancedBy(400f, gap).to,
        )
        assertEquals("and still records the distance travelled", 400f, stale.advancedBy(400f, gap).offset, 0.001f)

        val empty = dragOf(0).copy(heights = emptyList(), snapshot = emptyList())
        assertEquals("a snapshot with nothing in it", 0, empty.advancedBy(400f, gap).to)
    }

    @Test
    fun `a press has no heading, and a drag takes the direction of the thumb's last push`() {
        val arm = 8f
        val pressed = dragOf(0).draggedBy(3f, gap, arm)
        assertEquals("three pixels is a thumb resting on the grip, not a drag", 0, pressed.heading)

        val down = pressed.draggedBy(20f, gap, arm)
        assertEquals("past the arming distance, downward", 1, down.heading)

        val up = down.draggedBy(-40f, gap, arm)
        assertEquals("and the last push is what counts, not where the card has got to", -1, up.heading)

        val backHome = up.draggedBy(20f, gap, arm)
        assertEquals(
            "a gesture brought back to where it started is a press again, and stops the list",
            0,
            backHome.heading,
        )
    }

    @Test
    fun `THE HEADING IS THE THUMB'S AND NEVER THE CARD'S - a scroll may not re-assert a direction`() {
        val arm = 8f
        var state = dragOf(4).draggedBy(-30f, gap, arm)
        assertEquals("heading up", -1, state.heading)

        val beforeScroll = state
        state = state.advancedBy(-600f, gap)
        assertEquals("the scroll did not touch the heading", beforeScroll.heading, state.heading)
        assertEquals("nor the thumb's own travel", beforeScroll.travel, state.travel, 0.001f)
        assertTrue("but the card has travelled", state.offset < beforeScroll.offset)

        val reversed = state.draggedBy(40f, gap, arm)
        assertEquals("and one push the other way turns the list round at once", 1, reversed.heading)
    }

    @Test
    fun `the auto-scroll band lies wholly beyond the grip, on the side the thumb is pushing`() {
        val grip = 48f
        val lookahead = 96f

        val down = dwAutoScrollBand(1, grip, lookahead)
        assertEquals("it starts where the grip ends", grip, down.top, 0.001f)
        assertEquals("and is one lookahead tall", lookahead, down.bottom - down.top, 0.001f)

        val up = dwAutoScrollBand(-1, grip, lookahead)
        assertEquals("upward it ends where the grip begins", 0f, up.bottom, 0.001f)
        assertEquals("and is the same height", lookahead, up.bottom - up.top, 0.001f)

        assertTrue("neither band overlaps the grip", down.top >= grip && up.bottom <= 0f)
        assertEquals("and a gesture with no heading asks for nothing", 0f, dwAutoScrollBand(0, grip, lookahead).height, 0.001f)
    }

    @Test
    fun `the dragged row carries both halves of its travel and its neighbours carry one step`() {
        val state = dragOf(0).advancedBy(100f, gap).advancedBy(250f, gap)
        assertEquals("the card is where the thumb and the list between them have put it", 350f, state.shiftFor(0, gap), 0.001f)

        val step = heights[0] + gap
        assertEquals("every row it has passed opens the gap behind it", -step, state.shiftFor(1, gap), 0.001f)
        assertEquals("up to and including the destination", -step, state.shiftFor(state.to, gap), 0.001f)
        assertEquals("and nothing beyond it moves", 0f, state.shiftFor(state.to + 1, gap), 0.001f)

        val nothing: DwDragState? = null
        assertEquals("with no drag in flight, nothing is displaced", 0f, nothing.shiftFor(2, gap), 0.001f)
    }
}
