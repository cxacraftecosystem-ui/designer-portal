package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecordDerivationTest {

    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun `a date one day ahead derives nothing, not zero`() {
        assertNull(deriveExperienceYears(LocalDate.of(2026, 8, 24), today))
        assertNull(deriveAgeYears(LocalDate.of(2026, 8, 24), today))
    }

    @Test
    fun `a date eleven months ahead derives nothing either`() {
        assertNull(deriveExperienceYears(LocalDate.of(2027, 8, 22), today))
        assertNull(deriveAgeYears(LocalDate.of(2027, 8, 22), today))
    }

    @Test
    fun `a date a full year ahead was already refused, and still is`() {
        assertNull(deriveExperienceYears(LocalDate.of(2027, 8, 23), today))
        assertNull(deriveAgeYears(LocalDate.of(2027, 8, 23), today))
    }

    @Test
    fun `today itself is zero and not null`() {
        assertEquals(0, deriveExperienceYears(today, today))
        assertEquals(0, deriveAgeYears(today, today))
    }

    @Test
    fun `a date earlier this year is zero`() {
        assertEquals(0, deriveExperienceYears(LocalDate.of(2026, 1, 1), today))
    }

    @Test
    fun `the day before an anniversary is still the lower year`() {
        assertEquals(31, deriveExperienceYears(LocalDate.of(1994, 8, 24), today))
        assertEquals(32, deriveExperienceYears(LocalDate.of(1994, 8, 23), today))
    }

    @Test
    fun `a leap day is read the way the server reads it`() {
        assertEquals(25, deriveAgeYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 2, 28)))
        assertEquals(26, deriveAgeYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `experience is refused above ninety and accepted at ninety`() {
        assertEquals(90, deriveExperienceYears(LocalDate.of(1936, 8, 23), today))
        assertNull(deriveExperienceYears(LocalDate.of(1935, 8, 23), today))
    }

    @Test
    fun `age is refused above a hundred and thirty and accepted at a hundred and thirty`() {
        assertEquals(130, deriveAgeYears(LocalDate.of(1896, 8, 23), today))
        assertNull(deriveAgeYears(LocalDate.of(1895, 8, 23), today))
    }

    @Test
    fun `no date is no answer`() {
        assertNull(deriveExperienceYears(null, today))
        assertNull(deriveAgeYears(null, today))
    }
}
