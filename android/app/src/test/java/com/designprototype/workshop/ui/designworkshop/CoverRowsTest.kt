package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner decision C1: the sanctioned amount prints on the cover. The port of
 * `report_builder.cover_rows`, pinned to the same cases as
 * `test_cover_rows_reserves_a_row_for_a_pinned_field_and_keeps_declared_order`.
 */
class CoverRowsTest {
    @Test
    fun aPinnedFieldKeepsARowInsideTheCapAndDeclaredOrderHolds() {
        assertEquals(setOf("sanctionAmount"), COVER_PINNED_KEYS)
        val keyed = (0 until 12).map { Triple("k$it", "L$it", "v") } +
            Triple("sanctionAmount", "Amt", "1")
        val printed = coverRows(keyed).map { it.first }
        assertEquals((0 until 9).map { "L$it" } + "Amt", printed)
        assertEquals(COVER_INFO_ROWS, printed.size)
    }

    @Test
    fun underTheCapNothingMoves() {
        val short = listOf(
            Triple("a", "A", "1"),
            Triple("sanctionAmount", "Amt", "2"),
            Triple("b", "B", "3"),
        )
        assertEquals(listOf("A" to "1", "Amt" to "2", "B" to "3"), coverRows(short))
    }
}
