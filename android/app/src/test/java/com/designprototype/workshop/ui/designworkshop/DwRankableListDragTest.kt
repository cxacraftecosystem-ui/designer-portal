package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DRAG ARITHMETIC UNDER [DwRankableList], INCLUDING THE ONE PROPERTY EDGE AUTO-SCROLL RESTS ON.
 *
 * ── WHY THIS FILE EXISTS AT ALL ───────────────────────────────────────────────────────────────
 *
 * The gesture had NO test of any kind until 2026-08-27, on either client. That is not an oversight
 * anybody made once: nothing in this project can compose a Compose tree in a unit test, so every
 * rule that lived inside a `detectDragGestures` callback was only ever exercised by somebody looking
 * at a phone. The rules moved out into functions — [advancedBy], [draggedBy], [dwAutoScrollBand],
 * [shiftFor] — for exactly the reason `numberedRowsAfterEdit` did in `DwBulletListField`: a
 * judgement that cannot be called from a test is a judgement nothing will notice the loss of.
 *
 * ── WHAT IT PINS, AND WHY EACH ONE IS INVISIBLE ───────────────────────────────────────────────
 *
 * The reorder these functions decide is what a ministry report prints as a ranking, and every way
 * they can be wrong looks like a slightly odd animation rather than a wrong write:
 *
 *   • **A scroll and a thumb are the same pixels.** This is the whole of what makes auto-scroll
 *     legal beside rule 1's "the geometry is snapshotted once". If the two ever diverged, a designer
 *     who reached the ninth row by holding a thumb at the edge would commit a different arrangement
 *     from one who dragged there, on the same list.
 *   • **A scroll does not invalidate the snapshot.** The tempting repair is to re-measure the rows,
 *     which is what rule 1 forbids and which would put a motor on the oscillation it prevents.
 *   • **The heading is the thumb's and not the card's.** After a long auto-scroll the card's offset
 *     is hundreds of pixels from where it began; a direction taken from that sign would refuse to
 *     let the list come back up.
 *   • **The band is beyond the grip and never over it.** A rectangle that covers the grip is one the
 *     ancestor can satisfy by scrolling AWAY from the edge the thumb is pressing.
 *
 * ── WHAT IT DOES NOT CLAIM ────────────────────────────────────────────────────────────────────
 *
 * Nothing here composes anything, asks a scroll container for anything, or proves that a request was
 * honoured. What the ancestor does with the rectangle is the platform's, and the file's own header
 * records that the design is correct whether the responder scrolls once per call or keeps going.
 * `frontend/e2e/drag-autoscroll-unit.spec.ts` holds the web half of the same arithmetic; the two are
 * deliberately separate files because the two clients reach the same behaviour by different routes
 * and neither one's numbers may be read off the other's.
 */
class DwRankableListDragTest {

    /**
     * Five rows of DELIBERATELY UNEQUAL height, and a 12dp gap — [DwRankableList]'s `ROW_GAP` at
     * mdpi, which is the density unit tests run at.
     *
     * Uniform rows would let a wrong formula pass. A review card's height depends on whether its
     * owner has written a paragraph into it, which is the case the header's `heights` map exists for.
     */
    private val heights = listOf(60f, 180f, 40f, 60f, 60f)
    private val gap = 12f

    /** Row tops as [advancedBy] derives them: a running sum of the heights plus the gap. */
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
        // Not a tautology: it is the frame of reference every other assertion here is written in,
        // and it is the one thing in this file a reader can check against the composable by eye.
        var running = 0f
        heights.forEachIndexed { index, height ->
            assertEquals("row $index's top", tops[index], running, 0.001f)
            running += height + gap
        }
    }

    @Test
    fun `the target is the furthest row whose centre the dragged centre has passed`() {
        // Row 0's centre is at 30; row 1's is at 162, so 131px of travel is one short of it.
        assertEquals(0, dragOf(0).advancedBy(131f, gap).to)
        assertEquals(1, dragOf(0).advancedBy(133f, gap).to)
        // Row 4's centre is at 418; row 2's is at 284, so 135px up clears it and row 1's is further.
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
        /*
          THE RECONCILIATION THAT MAKES EDGE AUTO-SCROLL LEGAL, asserted rather than argued.

          `advancedBy` is called from two places — a pointer sample in `onDrag`, and a scroll
          measured by `onGloballyPositioned` on the list root — and the design's central claim is
          that it may not tell them apart. A designer who reaches the fourth row by dragging a thumb
          200px and one who holds it at the edge of the screen until 200px of list has gone by
          underneath must land in the same place, or the same gesture means two different things
          depending on how tall the phone happens to be.
        */
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
        /*
          THE OTHER HALF, AND THE ONE THAT LETS RULE 1 STAND.

          After a scroll the rows really are somewhere else on the screen, so the instinct is that
          the snapshot must be re-taken. It must not: re-measuring is what rule 1 forbids, and it
          would be worse here than anywhere, because the auto-scroll is DRIVEN by the target index —
          the oscillation would have a motor attached to it.

          It does not need to be, because [advancedBy] derives every top from the snapshotted heights
          and compares rows only with each other, so a translation that moves all of them equally
          cannot change the answer. Here that is stated the way the composable actually experiences
          it: the scrolled pixels arrive as travel, and the geometry is untouched.
        */
        val scrolledThere = dragOf(0)
            .advancedBy(50f, gap) // the thumb, before the auto-scroll armed
            .advancedBy(120f, gap) // and then the list, three times, under a thumb that stopped
            .advancedBy(120f, gap)
            .advancedBy(60f, gap)
        val draggedThere = dragOf(0).advancedBy(350f, gap)

        assertEquals("the same destination", draggedThere.to, scrolledThere.to)
        assertEquals("from the same heights", heights, scrolledThere.heights)
        assertEquals("and the same arrangement, untouched", draggedThere.snapshot, scrolledThere.snapshot)
    }

    @Test
    fun `the snapshot can outlive the list it was taken of, and answers rather than throws`() {
        /*
          A refresh, a colleague's row arriving on a sync, a section deleted in another tab. This is
          called from inside a gesture callback and now also from a layout callback, and an exception
          in either takes the screen down rather than losing a drag. The release path is what
          abandons the gesture, and it can only do that if the gesture survived to reach it.
        */
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
        /*
          THE FAILURE THIS PINS, AND IT IS THE ONE THAT COST THE FIRST DESIGN.

          Taking the direction from the sign of `offset` reads naturally and traps the designer at the
          bottom of the list. Auto-scrolling 600px down leaves `offset` at +600; a thumb then dragged
          up to the top edge of a 640px screen brings it back only to about +100, still positive — so
          the band would still be asked for BELOW the grip, and the list would refuse to come back up
          while the designer pushed at the top of the screen. `heading` is the thumb's last push and
          `advancedBy` — which is what a scroll goes through — deliberately does not touch it.
        */
        val arm = 8f
        var state = dragOf(4).draggedBy(-30f, gap, arm)
        assertEquals("heading up", -1, state.heading)

        val beforeScroll = state
        state = state.advancedBy(-600f, gap) // six hundred pixels of list, under a thumb that held still
        assertEquals("the scroll did not touch the heading", beforeScroll.heading, state.heading)
        assertEquals("nor the thumb's own travel", beforeScroll.travel, state.travel, 0.001f)
        assertTrue("but the card has travelled", state.offset < beforeScroll.offset)

        val reversed = state.draggedBy(40f, gap, arm)
        assertEquals("and one push the other way turns the list round at once", 1, reversed.heading)
    }

    @Test
    fun `the auto-scroll band lies wholly beyond the grip, on the side the thumb is pushing`() {
        /*
          THE TWO FAILURES THIS PINS, BOTH OF THEM SILENT.

          A band that INCLUDED the grip is a request the ancestor can satisfy by scrolling away from
          the edge the thumb is pressing — the list would run backwards under a designer pushing
          forwards. And a band measured from the CARD rather than the grip is a rectangle that can be
          taller than the viewport on a review card with a paragraph in it, which the platform's
          default spec correctly declines to scroll for at all: auto-scroll would go missing on
          exactly the rows that need it most.
        */
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
        /*
          WHAT THE SCREEN DOES WITH ALL OF THE ABOVE. The dragged card's `translationY` is `offset`,
          which by now includes every auto-scrolled pixel — that is what keeps it under a thumb that
          did not move, because the card is laid out normally and travels with the list. Drop those
          pixels and the card slides away upward at exactly the speed the list is running at.

          The neighbours move by ONE step, and the step carries the gap: a card sliding past its
          neighbour has to clear the neighbour AND the space between them, or the two overlap by 12dp
          at every swap.
        */
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
