package com.designprototype.workshop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Retry / Download buttons on every media card must be clearly visible (owner, 2026-10-17). The
 * button's edge must stand out from the card and from surface-50 at 3:1 or better (WCAG 1.4.11), and
 * its ink must read on its own ground at 4.5:1 or better (WCAG 1.4.3), in light AND dark.
 */
class MediaActionColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(name: String, colors: MediaActionColors, card: Color, surface50: Color) {
        for ((groundName, ground) in listOf("card" to card, "surface-50" to surface50)) {
            val edge = contrast(colors.edge, ground)
            assertTrue("$name edge vs $groundName is $edge, needs 3:1", edge >= 3.0)
        }
        val ink = contrast(colors.ink, colors.ground)
        assertTrue("$name ink vs ground is $ink, needs 4.5:1", ink >= 4.5)
        val disabledInk = contrast(colors.disabledInk, colors.disabledGround)
        assertTrue("$name disabled ink vs ground is $disabledInk", disabledInk >= 3.0)
    }

    @Test
    fun lightButtonIsVisibleOnTheCard() {
        check("light", MediaActionColors.Light, FieldPalette.CardLight, FieldPalette.Surface50Light)
    }

    @Test
    fun darkButtonIsVisibleOnTheCard() {
        check("dark", MediaActionColors.Dark, FieldPalette.CardDark, FieldPalette.Surface50Dark)
    }

    @Test
    fun theThemePicksItsOwnPair() {
        assertTrue(MediaActionColors.forTheme(isDark = false) == MediaActionColors.Light)
        assertTrue(MediaActionColors.forTheme(isDark = true) == MediaActionColors.Dark)
    }
}
