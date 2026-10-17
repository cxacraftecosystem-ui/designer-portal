package com.designprototype.workshop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The purple "N file(s) attached" card, in light AND dark (release smoke 2026-10-17, dp-final/26 and
 * 47). Light mode drew "Clear attachments" in the theme's purple on the purple tile; dark mode drew
 * the title and status line in the theme's dark canvas colours on the tile; and the "Upload failed"
 * line was the scheme's error red, about 2:1 there. Every word on the tile now takes the tile's own
 * ink, and this measures each pair at WCAG AA: 4.5:1 for text, 3:1 for a button's outline.
 */
class BrandTileContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(theme: String, tokens: FieldTokens) {
        val tile = tokens.brandTile
        val pairs = listOf(
            "title (onBrandTile)" to tokens.onBrandTile,
            "status line (onBrandTileMuted)" to tokens.onBrandTileMuted,
            "failed line (errorOnBrandTile)" to tokens.errorOnBrandTile,
        )
        for ((name, ink) in pairs) {
            val ratio = contrast(ink, tile)
            assertTrue("$theme $name on the tile is $ratio, needs 4.5:1", ratio >= 4.5)
        }
        // The on-tile outlined button ("Clear attachments", "Show fewer"): the ink at 70% over the tile.
        val outline = tokens.onBrandTile.copy(alpha = 0.7f).compositeOver(tile)
        val edge = contrast(outline, tile)
        assertTrue("$theme on-tile outline is $edge, needs 3:1", edge >= 3.0)
        // The failed line still has to read as an error, not as more tile ink.
        assertTrue("$theme errorOnBrandTile must be red", tokens.errorOnBrandTile.red > tokens.errorOnBrandTile.blue)
    }

    @Test
    fun lightTileIsReadable() = check("light", LightFieldTokens)

    @Test
    fun darkTileIsReadable() = check("dark", DarkFieldTokens)

    @Test
    fun theCardUsesTheTileInkNotTheThemeCanvas() {
        val source = File("src/main/java/com/designprototype/workshop/MainActivity.kt").readText()
        val start = source.indexOf("file(s) attached\"")
        assertTrue("the attached-files card must exist", start >= 0)
        val card = source.substring(start - 2000, start + 6000)
        assertFalse("the card must not draw in the theme's canvas colour", card.contains("color = Canvas"))
        assertFalse("the card must not draw in the theme's card colour", card.contains("color = SurfaceCard"))
        assertTrue("Clear attachments must use the on-tile outlined variant", card.contains("onTileBorder(tileInk)"))
        assertTrue("the failed line must use the tile's error rung", card.contains("errorOnBrandTile"))
    }

    @Test
    fun aBatchThatHasStoppedSaysHowManyDidNotUpload() {
        val oneFailed = attachedUploadsStatus(total = 1, done = 0, failed = 1, fraction = 0f)
        assertEquals("1 file didn't upload.", oneFailed.line)
        assertFalse("no bar once nothing is uploading", oneFailed.showProgress)
        assertTrue(oneFailed.failed)

        val someFailed = attachedUploadsStatus(total = 5, done = 3, failed = 2, fraction = 0.6f)
        assertEquals("2 files didn't upload.", someFailed.line)
        assertFalse(someFailed.showProgress)
    }

    @Test
    fun aBatchStillMovingKeepsItsBar() {
        val moving = attachedUploadsStatus(total = 3, done = 1, failed = 1, fraction = 0.5f)
        assertEquals("Uploading… 50% (1/3 files done)", moving.line)
        assertTrue(moving.showProgress)
        assertFalse(moving.failed)

        val done = attachedUploadsStatus(total = 2, done = 2, failed = 0, fraction = 1f)
        assertEquals("All uploaded ✓ — ready to save", done.line)
        assertFalse(done.showProgress)
    }
}
