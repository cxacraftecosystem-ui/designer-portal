package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.dwMoveTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE DRAG GRIP ON A STAGE'S COLLECTION ROWS — the arithmetic and the words, which is all of it that
 * can be wrong silently.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS FOR
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Until 2026-08-27 a designer could rearrange a stage's rows — Stage logs, Material usage, a costing
 * table — only with the two arrow buttons. The web has had a grip on the same form for longer, and
 * `StageScreen`'s comment justifying the absence argued from a dependency that had stopped applying:
 * it weighed up "a reorderable LazyColumn" for a list that is not a LazyColumn, and said "this module
 * has neither" a library nor a hand-rolled detector while `DwRankableList.kt` in the same package IS
 * a hand-rolled detector. The grip is now there.
 *
 * A JVM test cannot compose a `@Composable`, so the pointer plumbing is the instrumented suite's
 * ground. What it CAN pin is everything that decides whether the gesture tells the truth:
 *
 *  * **the two controls mean one thing by a move.** The arrows are the accessible path and they must
 *    keep writing the same ordinal as the drag. They do, because both go through `dwMovedTo` — and
 *    that has to keep meaning what `dwMoveTo` means, or one handset would hold two different ideas
 *    of "put this third". The web had exactly this divergence and had to correct itself: its arrows
 *    SWAPPED two rows while a drag MOVED one, identical for the ±1 an arrow asks for and a different
 *    list entirely for a drag across five rows.
 *  * **the target index under the thumb**, on a list whose rows are wildly unequal — one row expanded
 *    into a whole form beside collapsed 68dp header strips is the ordinary state of this screen, and
 *    it is the case where "half a row down" is a meaningless quantity.
 *  * **the words.** A rank that exists only as a place in a visual list is a rank a TalkBack user
 *    cannot read back, and a gesture that lifted a row, opened a gap and then quietly changed nothing
 *    reads as a broken phone and gets repeated.
 *
 * ORDER IS THE ORDINAL — the array position is what is sent as `ordinal`, what `save_stage` stores,
 * what the report builder sorts by and what prints in the .docx a ministry receives. That is why an
 * arithmetic slip here is not a cosmetic bug.
 */
class DwCollectionDragTest {

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // One move, two controls
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * THE REGRESSION GUARD THE WHOLE FEATURE RESTS ON. `dwMovedTo` is what the arrows and the grip
     * both commit through, and `dwMoveTo` is what the ranking list on the other screen commits
     * through. They are two functions because the collection list holds rows and the ranking list
     * holds ids; they may never be two BEHAVIOURS.
     *
     * Swept rather than sampled, and deliberately past both ends: a drag that finishes with the thumb
     * off the bottom of the screen asks for an out-of-range index, and it is an ordinary thing a
     * thumb does, not an error.
     */
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

    /**
     * A MOVE, NOT A SWAP. The distinction is invisible at ±1 and is the whole list at ±3, which is
     * exactly why it went unnoticed on the web for as long as it did: the arrows exercised only the
     * case where the two agree.
     */
    @Test
    fun `moving across the list carries the rows between along, rather than swapping two`() {
        val rows = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "d", "a", "e"), dwMovedTo(rows, 0, 3))
        // The swap this must NOT be.
        assertNotEquals(listOf("d", "b", "c", "a", "e"), dwMovedTo(rows, 0, 3))
    }

    /**
     * CLAMPED RATHER THAN REFUSED, and a no-op returns the list it was given. The arrows are already
     * disabled at the ends, so this is the drag's case — and a commit that fired for a move of zero
     * would stamp an "arrangement" every time a thumb rested on the grip while the page settled.
     */
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

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // Where the thumb is, on a list of wildly unequal rows
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The ordinary shape of this screen: four rows, the second one EXPANDED into a whole form.
     *
     * Heights in pixels, as `onSizeChanged` reports them; the 8dp gap is [gap]. Tops are therefore
     * 0 / 76 / 684 / 760 and centres 34 / 376 / 718 / 794.
     */
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

    /**
     * THE PROPERTY THAT MATTERS ON THIS LIST, AND THE ONE AN "OBVIOUS" IMPLEMENTATION GETS WRONG.
     *
     * Passing an expanded neighbour costs more than half of THAT row's height, not half of the
     * dragged row's and not one row-step. An implementation that counted rows travelled, or that
     * divided the offset by a single row height, would put the row four places down the moment the
     * thumb cleared its own card — the designer would watch a costing line shoot to the bottom of
     * the table.
     */
    @Test
    fun `a collapsed row passes an expanded one only when their centres cross`() {
        val drag = dragFrom(0)
        // Well past its own height (68px) and nowhere near the expanded row's centre.
        assertEquals(0, drag.dwAdvancedBy(100f, gap).to)
        assertEquals(0, drag.dwAdvancedBy(341f, gap).to)
        // 34 + 343 = 377, just past the expanded row's centre at 376.
        assertEquals(1, drag.dwAdvancedBy(343f, gap).to)
        assertEquals(2, drag.dwAdvancedBy(700f, gap).to)
        assertEquals(3, drag.dwAdvancedBy(800f, gap).to)
    }

    /** The same rule in the other direction, which is a separate clause and so a separate risk. */
    @Test
    fun `dragging upward takes the target when the centre passes the other centre`() {
        val drag = dragFrom(3)
        assertEquals(3, drag.dwAdvancedBy(-75f, gap).to)
        assertEquals(2, drag.dwAdvancedBy(-100f, gap).to)
        assertEquals(1, drag.dwAdvancedBy(-450f, gap).to)
        assertEquals(0, drag.dwAdvancedBy(-800f, gap).to)
    }

    /**
     * A THUMB DRAGGED OFF THE END OF THE LIST LANDS ON THE END OF THE LIST. The target is chosen from
     * the rows that exist, so no amount of travel can name an index that is not there — which is
     * what stops a gesture committing against a row nobody has.
     */
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

    /**
     * THE OFFSET ACCUMULATES. `detectDragGestures` reports a DELTA per event, not a position, so an
     * implementation that assigned rather than added would make the row follow the last few pixels
     * of the gesture and sit back at its start.
     */
    @Test
    fun `the offset is the sum of the deltas, not the last one`() {
        val moved = dragFrom(0)
            .dwAdvancedBy(100f, gap)
            .dwAdvancedBy(150f, gap)
            .dwAdvancedBy(100f, gap)
        assertEquals(350f, moved.offset, 0.001f)
        assertEquals(1, moved.to)
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // What the rows do while a finger is down
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * THE GAP OPENS WHERE THE ROW IS GOING, AND NOWHERE ELSE. Rows outside the span between `from`
     * and `to` must not move: a list that shuffled everything below the thumb would make the target
     * impossible to aim at.
     *
     * AND THE GAP TRAVELS WITH THE ROW — the shift is one dragged-row height PLUS one gap, or the two
     * cards overlap by a gap at every swap.
     */
    @Test
    fun `only the rows between where it came from and where it is going move`() {
        val drag = dragFrom(0).copy(to = 2, offset = 700f)
        assertEquals(700f, drag.dwShiftFor(0, gap), 0.001f)
        assertEquals(-76f, drag.dwShiftFor(1, gap), 0.001f)
        assertEquals(-76f, drag.dwShiftFor(2, gap), 0.001f)
        assertEquals(0f, drag.dwShiftFor(3, gap), 0.001f)
    }

    /** The upward case, where the displaced rows move down by the same step. */
    @Test
    fun `dragging upward pushes the displaced rows down`() {
        val drag = dragFrom(3).copy(to = 1, offset = -450f)
        assertEquals(0f, drag.dwShiftFor(0, gap), 0.001f)
        assertEquals(76f, drag.dwShiftFor(1, gap), 0.001f)
        assertEquals(76f, drag.dwShiftFor(2, gap), 0.001f)
        assertEquals(-450f, drag.dwShiftFor(3, gap), 0.001f)
    }

    /**
     * AT REST, NOTHING MOVES. The extension is declared on the NULLABLE state precisely so the call
     * site cannot forget this case; the symptom if it could would be every row of the stage drawn
     * a few hundred pixels out of place.
     */
    @Test
    fun `with no drag in flight every row sits still`() {
        val none: DwCollectionDrag? = null
        (0..4).forEach { assertEquals(0f, none.dwShiftFor(it, gap), 0.001f) }
    }

    /**
     * A ROW THAT HAS NEVER BEEN MEASURED MOVES NOTHING RATHER THAN MOVING WRONGLY. `heights` is filled
     * by `onSizeChanged`, so the first frame of a freshly composed list can be missing entries — and
     * a drag started in that frame must degrade to "nothing visibly happens", not to a list flung
     * about by a height of zero read as a real measurement.
     */
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
        // The travel is still recorded — a release then finds `to == from` and commits nothing,
        // which is the honest outcome — but no target is invented out of heights nobody measured.
        val advanced = unmeasured.dwAdvancedBy(50f, gap)
        assertEquals(50f, advanced.offset, 0.001f)
        assertEquals(1, advanced.to)
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // The words
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * RULE 4. The one sentence both controls speak, so a TalkBack user is not taught two vocabularies
     * for one act depending on which control they can reach.
     *
     * The position is PRINTED 1-BASED, because "position 3 of 9" is what a designer says out loud,
     * what the provenance page calls "row 3" and what the report prints. An off-by-one here is a
     * sentence that disagrees with the number on the card beside it.
     */
    @Test
    fun `a move is announced with the row's name and its new place, counted from one`() {
        val said = dwRowMovedSentence("Teak batten 50x25", landed = 2, total = 9)
        assertEquals("Teak batten 50x25 moved to position 3 of 9.", said)
        assertTrue(said.contains("Teak batten 50x25"))
    }

    /**
     * RULE 2's OTHER HALF. A gesture whose list changed underneath it is abandoned, and abandoning it
     * silently is the failure: the row visibly lifted, the gap visibly opened, and then nothing
     * happened. A designer reads that as a broken phone and does it again.
     *
     * AND IT MAY NOT NAME A POSITION. The whole reason the gesture was dropped is that its indices
     * no longer address the rows it measured — a sentence saying where the row went would be the
     * confident wrong answer the abandonment exists to avoid.
     */
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

    /**
     * THE TWO OUTCOMES ARE TWO SENTENCES. One string reused for both is how "your row moved" and
     * "your row did not move" become indistinguishable — the silent-emptiness class, on a control
     * whose only output is a sentence.
     */
    @Test
    fun `a move and a refused move do not sound alike`() {
        val moved = dwRowMovedSentence("Prototype 2", landed = 0, total = 4)
        val refused = dwRowDragAbandonedSentence("Prototype 2")
        assertNotEquals(moved, refused)
        assertTrue(moved.isNotBlank())
        assertTrue(refused.isNotBlank())
    }
}
