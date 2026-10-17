package com.designprototype.workshop.ui

import com.designprototype.workshop.DASH_MIN_CARD_DP
import com.designprototype.workshop.dashboardInnerColumns
import com.designprototype.workshop.dashboardMegaColumns
import com.designprototype.workshop.dashboardTileClickLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A TAP ANYWHERE ON A CARD OPENS IT (owner, 2026-10-17), as documentation-portal does.
 *
 * WHY THE FIRST PASS DID NOT TAKE: the 2026-10-16 change put whole-card taps on the record ROWS and
 * pinned them in `RecordOpenTest`, but never on `DashboardActionCard` — the tiles a designer meets
 * first — whose Column carried padding and no click. Nothing asserted the tile, so a tap on its icon
 * or label did nothing and the owner read every card as dead. These assertions read the source, for
 * the reason `RecordOpenTest` gives: the cards are file-private composables.
 */
class OpenableCardTest {

    private val main by lazy { mainSource("MainActivity.kt") }

    private fun body(signature: String, source: String = main): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone", start >= 0)
        val next = source.indexOf("\n@Composable", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }

    @Test
    fun theWholeDashboardTileIsItsPrimaryActionAndUpdateStaysItsOwnButton() {
        val card = body("private fun DashboardActionCard(")
        assertTrue(card.contains(".openable(title = tile.label, shape = shape, clickLabel = dashboardTileClickLabel(tile.primaryLabel, tile.label)) { tile.onPrimary() }"))
        assertTrue("the click sits inside the card, before the padding",
            card.indexOf(".openable(") < card.indexOf(".padding(14.dp)"))
        assertTrue("Update consumes its own tap", card.contains("onClick = update"))
        assertEquals("New Artisan", dashboardTileClickLabel("New", "Artisan"))
    }

    @Test
    fun theOpenableModifierClipsThenClicksWithARoleALabelAnd48dp() {
        val src = mainSource("ui/OpenableCard.kt")
        val chain = src.substringAfter("else this").substringBefore("\n\n")
        val clip = chain.indexOf(".clip(shape)")
        val click = chain.indexOf(".clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)")
        val floor = chain.indexOf(".heightIn(min = OPENABLE_MIN_TARGET)")
        assertTrue("clip, then click, then the 48dp floor", clip in 0 until click && click < floor)
        assertEquals(48f, OPENABLE_MIN_TARGET.value)
        assertTrue(src.contains("clickLabel: String = RecordOpen.openClickLabel(title)"))
    }

    @Test
    fun cardsThatOpenSomethingCarryTheClickInsideTheirClip() {
        assertTrue("My Activity", main.contains(".openable(item.title, RoundedCornerShape(14.dp)) { onOpen(item.mode, item.id) }"))
        assertTrue("review queue heading", main.contains(".openable(item.label, RoundedCornerShape(8.dp), enabled = onOpen != null) { onOpen?.invoke() }"))
        assertTrue("review queue routes", main.contains("onOpenRecord = reviewOpenRecord"))
        assertTrue("admin tools", main.contains(".openable(entry.label, RoundedCornerShape(12.dp)) { onSectionChange(entry) }"))
        assertTrue("search hits", mainSource("ui/SearchScreen.kt").contains(".openable(row.title, MaterialTheme.shapes.medium, enabled = openable, onOpen = onOpen)"))
        assertTrue("data browser folders", mainSource("ui/DataBrowserScreen.kt").contains(".openable(folder.name, MaterialTheme.shapes.medium, onOpen = onOpen)"))
        assertTrue("inspection list", mainSource("ui/designworkshop/InspectionListScreen.kt").contains(".openable(workshop.title.ifBlank { \"Untitled workshop\" }, RoundedCornerShape(12.dp), onOpen = onOpen)"))
        assertTrue("stage index", mainSource("ui/designworkshop/StageIndexScreen.kt").contains(".openable(stage.title, RoundedCornerShape(8.dp), onOpen = onOpen)"))
    }

    @Test
    fun noCardPutsAnUnlabelledClickOnItsOuterModifier() {
        // The shape that loses the ripple's corner, the role and the label at once.
        val pattern = Regex("""modifier = Modifier\.fillMaxWidth\(\)\.clickable\(onClick = onOpen\)""")
        for (path in listOf("MainActivity.kt", "ui/DataBrowserScreen.kt", "ui/designworkshop/InspectionListScreen.kt", "ui/designworkshop/StageIndexScreen.kt")) {
            assertFalse(path, pattern.containsMatchIn(mainSource(path)))
        }
    }

    @Test
    fun theGroupHeaderIsAButtonThatSaysWhatItDoes() {
        val mega = body("private fun DashboardMegaCard(")
        assertTrue(mega.contains("role = Role.Button,"))
        assertTrue(mega.contains("onClickLabel = if (expanded) \"Hide \${group.title}\" else \"Show \${group.title}\","))
        assertTrue(mega.contains(".heightIn(min = 48.dp)"))
    }

    @Test
    fun anOpenGroupDrawsTwoTilesPerRowOnAPhoneAsDocumentationPortalDoes() {
        for (width in listOf(360, 393, 411, 600, 840, 1280)) {
            assertEquals("$width dp", 2, dashboardInnerColumns(width))
        }
        assertEquals(1, dashboardMegaColumns(411))
        assertEquals(2, dashboardMegaColumns(840))
        // 360dp: 328dp mega card less 2x14 padding = 300dp, which holds two 140dp tiles and a 12dp gap.
        assertTrue(300 >= 2 * DASH_MIN_CARD_DP + 12)
        val label = body("private fun CardButtonLabel(")
        assertTrue("button words wrap rather than clip", label.contains("maxLines = 2"))
    }
}

/** A main-source file, found from wherever Gradle runs the tests, with CRLF folded to LF. */
internal fun mainSource(relative: String): String {
    val tails = listOf(
        "src/main/java/com/designprototype/workshop/$relative",
        "app/src/main/java/com/designprototype/workshop/$relative",
        "android/app/src/main/java/com/designprototype/workshop/$relative",
    )
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (tail in tails) {
            val candidate = File(dir, tail)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n")
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of $tails found from ${File(".").absolutePath}")
}
