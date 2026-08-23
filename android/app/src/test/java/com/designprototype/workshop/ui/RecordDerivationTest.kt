package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * THE HANDSET'S PORT OF THE TWO RECORD DERIVATIONS — `derive_age` and `derive_experience_years`.
 *
 * ── WHY THIS FILE EXISTS, WHICH IS THE SAME REASON `DerivedFieldTest` EXISTS ──────────────────────
 *
 * The registry's own derivations (DAYS_BETWEEN / PRODUCT / SUM) are pinned by
 * `data/DerivedFieldTest`, and its header records what an unported derivation cost: the phone left a
 * box blank that the web and the server filled, and the blank printed in every report generated
 * before the next sync — which in this app may be a fortnight away, in an office, after the designer
 * has left the cluster. The two derivations HERE had no test on this client at all, in either
 * direction, and they are the ones a designer reads as a sentence under a date box rather than as a
 * value in a field: "Age 48", "32 years of experience, worked out from this — the figure the workshop
 * and the report use". A wrong number there is a number nobody can check against anything.
 *
 * ── THE DEFECT THAT WAS ACTUALLY SHIPPED, AND IS PINNED BELOW ─────────────────────────────────────
 *
 * `wholeYearsBetween` computed `Period.between(from, on).years` and tested the result against a band
 * beginning at 0. For a date UP TO 364 DAYS IN THE FUTURE that component is 0 rather than negative
 * (`Period.between(2027-08-22, 2026-08-23)` is `P-11M-30D`), so the band accepted it and the handset
 * answered **0** where the server answers **None** — the server computing the components by hand and
 * getting -1. Every reachable consequence was a sentence a designer would act on: "0 years of
 * experience" beside a stated 32, on a record every web surface reported as having no derivable
 * experience at all.
 *
 * It was reachable without touching any clock. `ArtisanCreate.craftStartDate` carries no upper bound
 * on purpose, a stored future date is seeded straight into form state, and `FieldDateField`'s
 * `maximum` gates only what is TYPED. So the guard is inside the derivation, and this file asserts it
 * there rather than at the input.
 *
 * ── EVERY CASE PASSES AN EXPLICIT `on` ───────────────────────────────────────────────────────────
 *
 * Never the default. The default is `LocalDate.now(ZoneOffset.UTC)`, and a test that leans on it is a
 * test that changes its answer on somebody's birthday — the exact class of wrongness the derivation
 * exists to avoid. The dates below are the server's golden cases (`frontend/e2e/fixtures/
 * record-derivation-cases.json`, regenerated from the real Python) so the three implementations can
 * be compared by reading one number in three files.
 */
class RecordDerivationTest {

    private val today = LocalDate.of(2026, 8, 23)

    // ── The future, which is where the handset and the server disagreed ──────────────────────────

    @Test
    fun `a date one day ahead derives nothing, not zero`() {
        // `Period.between(2026-08-24, 2026-08-23)` is `P-1D` — years component 0. This is the case
        // that reads as "0 years of experience" on a screen and as "no answer" everywhere else.
        assertNull(deriveExperienceYears(LocalDate.of(2026, 8, 24), today))
        assertNull(deriveAgeYears(LocalDate.of(2026, 8, 24), today))
    }

    @Test
    fun `a date eleven months ahead derives nothing either`() {
        // `P-11M-30D`. The whole window in which the years component is 0 while the date is ahead.
        assertNull(deriveExperienceYears(LocalDate.of(2027, 8, 22), today))
        assertNull(deriveAgeYears(LocalDate.of(2027, 8, 22), today))
    }

    @Test
    fun `a date a full year ahead was already refused, and still is`() {
        // `P-1Y` — the one future case the band caught on its own, kept so a regression that reverts
        // the guard cannot hide behind it.
        assertNull(deriveExperienceYears(LocalDate.of(2027, 8, 23), today))
        assertNull(deriveAgeYears(LocalDate.of(2027, 8, 23), today))
    }

    // ── Zero, which is a real answer and must survive the guard ──────────────────────────────────

    @Test
    fun `today itself is zero and not null`() {
        // An apprentice in their first month is 0 years' experience, and that is an answer the
        // participant table should carry. The guard is `isAfter`, strictly, for this reason.
        assertEquals(0, deriveExperienceYears(today, today))
        assertEquals(0, deriveAgeYears(today, today))
    }

    @Test
    fun `a date earlier this year is zero`() {
        assertEquals(0, deriveExperienceYears(LocalDate.of(2026, 1, 1), today))
    }

    // ── The anniversary correction, which is why `Period` is used at all ─────────────────────────

    @Test
    fun `the day before an anniversary is still the lower year`() {
        assertEquals(31, deriveExperienceYears(LocalDate.of(1994, 8, 24), today))
        assertEquals(32, deriveExperienceYears(LocalDate.of(1994, 8, 23), today))
    }

    @Test
    fun `a leap day is read the way the server reads it`() {
        // The golden case: 2000-02-29 on 2026-02-28 is 25 on the server and on the web. A
        // `ChronoUnit.DAYS / 365` would drift a day every four years and answer 26 here.
        assertEquals(25, deriveAgeYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 2, 28)))
        assertEquals(26, deriveAgeYears(LocalDate.of(2000, 2, 29), LocalDate.of(2026, 3, 1)))
    }

    // ── The bands, which differ between the two functions on purpose ─────────────────────────────

    @Test
    fun `experience is refused above ninety and accepted at ninety`() {
        // 0..90 mirrors `fromref("experienceYears", ..., min_value=0, max_value=90)` exactly:
        // `validate_entry` re-coerces every field on every save, so a hydrated 91 would become a
        // refused answer on a box nobody typed in.
        assertEquals(90, deriveExperienceYears(LocalDate.of(1936, 8, 23), today))
        assertNull(deriveExperienceYears(LocalDate.of(1935, 8, 23), today))
    }

    @Test
    fun `age is refused above a hundred and thirty and accepted at a hundred and thirty`() {
        assertEquals(130, deriveAgeYears(LocalDate.of(1896, 8, 23), today))
        assertNull(deriveAgeYears(LocalDate.of(1895, 8, 23), today))
    }

    // ── The missing date ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `no date is no answer`() {
        // A blank box and "zero years" are different statements, and the second is one this app would
        // be making up. Callers test for null, never for zero.
        assertNull(deriveExperienceYears(null, today))
        assertNull(deriveAgeYears(null, today))
    }
}
