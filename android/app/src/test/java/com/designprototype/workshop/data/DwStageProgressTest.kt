package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The stage index header's one rule: the bar, the percentage and "N of M stages complete" all count
 * stages. The 0.0.19 smoke printed "0% overall · 9 of 22 stages complete" because the percentage
 * counted required fields; these pin the shape that produced it so it cannot come back.
 */
class DwStageProgressTest {
    private fun score(key: String, requiredTotal: Int, requiredFilled: Int, missing: List<String> = emptyList()) =
        DwStageCompleteness(
            stageKey = key,
            number = 0,
            title = key,
            requiredTotal = requiredTotal,
            requiredFilled = requiredFilled,
            optionalTotal = 0,
            optionalFilled = 0,
            collectionCounts = emptyMap(),
            missing = missing,
        )

    /** Nine stages with nothing required, thirteen with every required field empty. */
    private val smoke = List(9) { score("EMPTY_$it", 0, 0) } +
        List(13) { score("OPEN_$it", 40, 0, List(3) { n -> "Field $n" }) }

    @Test
    fun percentAndCountAgreeOnTheSmokeWorkshop() {
        val progress = dwStageProgress(smoke)
        assertEquals(9, progress.stagesComplete)
        assertEquals(22, progress.stagesTotal)
        assertEquals(41, progress.percent)
        assertEquals(dwStageProgressPercent(progress.stagesComplete, progress.stagesTotal), progress.percent)
        assertEquals(39, progress.requiredLeft)
    }

    @Test
    fun captionLeadsWithStagesAndNeverPrintsTheFieldPercentage() {
        val line = dwStageProgressLine(dwStageProgress(smoke))
        assertEquals("9 of 22 stages complete · 39 required fields left", line)
        assertFalse(line, line.contains("%"))
    }

    @Test
    fun captionSingularAndDone() {
        val one = listOf(score("A", 1, 1), score("B", 2, 1, listOf("Date")))
        assertEquals("1 of 2 stages complete · 1 required field left", dwStageProgressLine(dwStageProgress(one)))
        val done = listOf(score("A", 1, 1), score("B", 0, 0))
        val progress = dwStageProgress(done)
        assertEquals(100, progress.percent)
        assertEquals("2 of 2 stages complete · no required fields left", dwStageProgressLine(progress))
    }

    @Test
    fun noStagesReadsZeroNotADivisionByZero() {
        assertEquals(0, dwStageProgress(emptyList()).percent)
        assertEquals(0, dwStageProgressPercent(0, 0))
        assertEquals(100, dwStageProgressPercent(5, 4))
    }
}
