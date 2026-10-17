package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six-box code on the handset — the owner's rules (2026-10-17), the web's
 * `e2e/otp-code-input-unit.spec.ts` restated: exactly six, digits only, a paste fills all six,
 * Backspace walks back, complete only at six ASCII digits, and no shake under reduced motion.
 */
class OtpCodeTest {

    @Test
    fun thereAreExactlySixBoxesNamedDigitNOfSix() {
        assertEquals(6, OtpCode.LENGTH)
        assertEquals(
            listOf("Digit 1 of 6", "Digit 2 of 6", "Digit 3 of 6", "Digit 4 of 6", "Digit 5 of 6", "Digit 6 of 6"),
            (0 until OtpCode.LENGTH).map { OtpCode.digitLabel(it) }
        )
    }

    @Test
    fun aTypedNonDigitIsRefusedAndNothingChanges() {
        listOf("a", "Z", " ", "-", "+", ".", "１").forEach { key ->
            assertEquals(key, "12", OtpCode.accept("12", "12$key"))
        }
    }

    @Test
    fun typingDigitsFillsTheBoxesInOrderAndStopsAtSix() {
        var value = ""
        "4829137".forEach { digit -> value = OtpCode.accept(value, value + digit) }
        assertEquals("482913", value)
        assertTrue(OtpCode.isComplete(value))
    }

    @Test
    fun aPasteOfTheCodeFillsAllSixAndSeparatorsGo() {
        assertEquals("739104", OtpCode.accept("", "739104"))
        assertEquals("739104", OtpCode.accept("", "739 104"))
        assertEquals("739104", OtpCode.accept("", "739-104"))
        assertEquals("739104", OtpCode.accept("12", "Your code is 739104."))
        assertEquals("739104", OtpCode.accept("", "73910455"))
    }

    @Test
    fun backspaceWalksTheCodeAway() {
        var value = "123456"
        repeat(8) { value = OtpCode.accept(value, value.dropLast(1)) }
        assertEquals("", value)
    }

    @Test
    fun completeMeansExactlySixAsciiDigits() {
        assertFalse(OtpCode.isComplete("12345"))
        assertTrue(OtpCode.isComplete("123456"))
        assertFalse(OtpCode.isComplete("1234567"))
        assertFalse(OtpCode.isComplete("12345a"))
        assertEquals("3456", OtpCode.onlyDigits("１２3a4-5 6"))
    }

    @Test
    fun talkBackHearsHowFarAlongTheCodeIs() {
        assertEquals("6-digit code, 6 boxes, empty", OtpCode.spoken("6-digit code", ""))
        assertEquals("6-digit code, 2 of 6 digits entered", OtpCode.spoken("6-digit code", "12"))
    }

    @Test
    fun theRefusalShakesExceptUnderReducedMotion() {
        assertEquals(emptyList<Float>(), OtpCode.shake(reducedMotion = true))
        assertEquals(listOf(0f, -6f, 6f, -6f, 6f, 0f), OtpCode.shake(reducedMotion = false))
    }
}
