package com.designprototype.workshop.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MANDATORY-FIELD ASTERISK IS RED, AND IT IS RED WITHOUT REPAINTING THE LABEL.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS PINS, AND WHY IT IS WORTH PINNING
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [dwRequiredMarked] is the whole of the 2026-08-30 instruction "wherever an asterisk has been used
 * in the application for the sake of denoting a mandatory field, have the asterisk in red". Roughly
 * twenty render points across the app now go through it, and — this is the part that needs a test —
 * they hand it EVERY label, not only the required ones. `SearchableSelectField` does not know
 * whether its caller's field is mandatory; it knows only that the mark, if there is one, is a
 * trailing `" *"` (see [DW_REQUIRED_MARK] and `FieldRenderer.fieldLabel`).
 *
 * So two failures sit one edit apart, and each is silent:
 *
 *  · A SPLIT THAT PAINTS TOO MUCH would colour whole labels red on every optional field in the app —
 *    twenty controls announcing an emergency. [redSpanOf] asserts the styled range is exactly the
 *    mark and never a character of the words.
 *  · A SPLIT THAT PAINTS TOO LITTLE returns the label unchanged and the instruction quietly does not
 *    ship: the asterisk is still there, still in label ink, and nothing fails.
 *
 * The colour is passed in rather than read from `MaterialTheme` precisely so this runs on the JVM.
 * `Theme.kt` is what binds it to `Error600` in light and `Error400` in dark, and its own comment
 * carries the reason ("success-600 and error-600 both fall under 4.5:1 against the dark canvas");
 * that binding is a theme fact and is not restated here.
 */
class RequiredMarkTest {

    private val mark = Color(0xFFDC2626)

    /** The one styled range in [label], as `text to startIndex`, or null when nothing is styled. */
    private fun redSpanOf(label: String): Pair<String, Int>? {
        val built = dwRequiredMarked(label, mark)
        val styled = built.spanStyles.filter { it.item.color == mark }
        if (styled.isEmpty()) return null
        assertEquals("exactly one styled range is expected", 1, styled.size)
        val range = styled.single()
        return built.text.substring(range.start, range.end) to range.start
    }

    @Test
    fun `the mark is styled and the words are not`() {
        val (styled, start) = redSpanOf("Craft *")!!
        // The leading space is INSIDE the span, matching the web's `RequiredMark`, so nothing can
        // later close the gap and produce "Craft*".
        assertEquals(DW_REQUIRED_MARK, styled)
        assertEquals("Craft".length, start)
        // The text itself is untouched: this is a colour change and never a copy change.
        assertEquals("Craft *", dwRequiredMarked("Craft *", mark).text)
    }

    @Test
    fun `a label with no mark is left entirely alone`() {
        assertEquals(null, redSpanOf("Local name"))
        assertEquals("Local name", dwRequiredMarked("Local name", mark).text)
    }

    /**
     * AN ASTERISK THAT IS NOT THE MARK IS NOT THE MARK. Only a TRAILING `" *"` is the convention
     * `fieldLabel` writes; a star anywhere else belongs to the label's own words, and colouring it
     * would invent a requirement the registry never declared.
     */
    @Test
    fun `an asterisk that is not trailing is not treated as the mark`() {
        assertEquals(null, redSpanOf("Rating * out of 5"))
        assertEquals(null, redSpanOf("Grade A*"))
    }

    /**
     * THE MULTI-SELECT'S LINE, which is the only label in the app whose mark is not last: it prints
     * "Crafts * (3 selected)". `SearchableMultiSelectField` marks the label and concatenates the
     * count, and this asserts that order is the one that works — handing the whole line in finds no
     * trailing mark at all.
     */
    @Test
    fun `the count is appended after the mark, not before it`() {
        assertEquals(null, redSpanOf("Crafts * (3 selected)"))
        val (styled, start) = redSpanOf("Crafts *")!!
        assertEquals(DW_REQUIRED_MARK, styled)
        assertEquals("Crafts".length, start)
    }

    /**
     * [dwWithoutRequiredMark] is what the cap-notice sentences and the picker sheet's heading use.
     * A sentence is not a label, and "Photographs * holds at most 8 entries" reads as a typo.
     */
    @Test
    fun `the mark is strippable for prose, and stripping is a no-op without one`() {
        assertEquals("Photographs", dwWithoutRequiredMark("Photographs *"))
        assertEquals("Photographs", dwWithoutRequiredMark("Photographs"))
        // Not greedy: only ONE mark is ever appended, so only one is ever removed.
        assertEquals("Odd label *", dwWithoutRequiredMark("Odd label * *"))
    }

    /**
     * THE HELPER IS APPLIED AT RENDER POINTS THAT SEE LABELS NOBODY VETTED — a questionnaire prompt
     * is typed by a designer, and `fieldLabel` appends the mark to whatever the registry holds. An
     * empty or mark-only label must not throw or produce a negative range.
     */
    @Test
    fun `degenerate labels do not throw`() {
        assertEquals("", dwRequiredMarked("", mark).text)
        val markOnly = dwRequiredMarked(DW_REQUIRED_MARK, mark)
        assertEquals(DW_REQUIRED_MARK, markOnly.text)
        assertTrue("a bare mark is still painted", markOnly.spanStyles.any { it.item.color == mark })
    }
}
