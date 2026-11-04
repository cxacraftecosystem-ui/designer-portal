package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.BulletListBlock
import com.designprototype.workshop.report.CalloutBlock
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.PageSize
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SignatureBlock
import com.designprototype.workshop.report.SpacerBlock
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TableColumn
import com.designprototype.workshop.report.TocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwReportPaginationTest {

    private val page = 1000f

    private fun block(
        blockIndex: Int,
        heightPx: Float,
        gapBeforePx: Float = 0f,
        breakBefore: Boolean = false,
        keepWithNext: Boolean = false,
        splittable: Boolean = false,
        breakOffsetsPx: List<Float> = emptyList(),
        repeatHeaderPx: Float = 0f,
        preferWhole: Boolean = false,
        isCover: Boolean = false,
    ) = FlowItem(
        blockIndex = blockIndex,
        heightPx = heightPx,
        gapBeforePx = gapBeforePx,
        breakBefore = breakBefore,
        keepWithNext = keepWithNext,
        splittable = splittable,
        breakOffsetsPx = breakOffsetsPx,
        repeatHeaderPx = repeatHeaderPx,
        preferWhole = preferWhole,
        isCover = isCover,
    )

    private fun shape(
        items: List<FlowItem>,
        contentPx: Float = page,
        coverContentPx: Float? = null,
    ): List<List<Int>> =
        packPages(items, PageBudget(contentPx, coverContentPx)).pages.map { p -> p.slices.map { it.blockIndex } }

    @Test
    fun `a block that fits stays on the page, and the page keeps its room for the next one`() {
        val packed = packPages(
            listOf(block(0, 300f), block(1, 300f, gapBeforePx = 20f)),
            PageBudget(page),
        )

        assertEquals(1, packed.pages.size)
        assertEquals(listOf(0, 1), packed.pages[0].slices.map { it.blockIndex })
        assertEquals(620f, packed.pages[0].usedPx, 0.001f)
        assertEquals(emptyList<Int>(), packed.divided)
        assertEquals(emptyList<ScaledBlock>(), packed.scaled)
    }

    @Test
    fun `the gap above a block is spent mid-page and eaten by a page break`() {
        val packed = packPages(
            listOf(block(0, 900f), block(1, 200f, gapBeforePx = 40f)),
            PageBudget(page),
        )

        assertEquals(2, packed.pages.size)
        assertEquals(0f, packed.pages[1].slices[0].gapBeforePx, 0.001f)
        assertEquals(200f, packed.pages[1].usedPx, 0.001f)
    }

    @Test
    fun `a block that overflows by a hair moves whole rather than being drawn past the paper`() {
        val items = listOf(block(0, 400f), block(1, 601f))
        val packed = packPages(items, PageBudget(page))

        assertEquals(listOf(listOf(0), listOf(1)), shape(items))
        assertTrue(packed.pages[0].usedPx <= page)
        assertTrue(packed.pages[1].usedPx <= page)
    }

    @Test
    fun `half a unit over is not over - sub-unit measurement must not start a page`() {
        assertEquals(listOf(listOf(0)), shape(listOf(block(0, 1000.2f))))
    }

    @Test
    fun `a paragraph is divided at its line boxes, and the pieces say which is which`() {
        val lines = (1..30).map { it * 50f }
        val packed = packPages(
            listOf(block(0, 1500f, splittable = true, breakOffsetsPx = lines)),
            PageBudget(page),
        )

        assertEquals(2, packed.pages.size)
        assertEquals(listOf(0), packed.divided)
        assertEquals(0, packed.pages[0].slices[0].part)
        assertEquals(2, packed.pages[0].slices[0].parts)
        assertEquals(0f, packed.pages[0].slices[0].offsetPx, 0.001f)
        assertEquals(1000f, packed.pages[0].slices[0].heightPx, 0.001f)
        assertEquals(1, packed.pages[1].slices[0].part)
        assertEquals(2, packed.pages[1].slices[0].parts)
        assertEquals(1000f, packed.pages[1].slices[0].offsetPx, 0.001f)
        assertEquals(500f, packed.pages[1].slices[0].heightPx, 0.001f)
    }

    @Test
    fun `a divided block is cut on a legal offset, never at the unit where the page ran out`() {
        val packed = packPages(
            listOf(block(0, 900f, splittable = true, breakOffsetsPx = listOf(120f, 380f, 640f))),
            PageBudget(500f),
        )

        assertEquals(380f, packed.pages[0].slices[0].heightPx, 0.001f)
        assertEquals(380f, packed.pages[1].slices[0].offsetPx, 0.001f)
    }

    @Test
    fun `a table repeats its header over every continuation, the way place_row does`() {
        val rows = listOf(80f, 160f, 240f, 320f, 400f, 480f, 560f, 640f, 720f)
        val packed = packPages(
            listOf(block(0, 800f, splittable = true, breakOffsetsPx = rows, repeatHeaderPx = 40f)),
            PageBudget(500f),
        )

        assertEquals(2, packed.pages.size)
        assertEquals(0f, packed.pages[0].slices[0].repeatHeaderPx, 0.001f)
        assertEquals(40f, packed.pages[1].slices[0].repeatHeaderPx, 0.001f)
        assertEquals(480f, packed.pages[0].slices[0].heightPx, 0.001f)
        assertEquals(320f, packed.pages[1].slices[0].heightPx, 0.001f)
        assertEquals(360f, packed.pages[1].usedPx, 0.001f)
    }

    @Test
    fun `an unsplittable block taller than a page gets a page of its own and is SCALED, not clipped`() {
        val packed = packPages(listOf(block(0, 200f), block(1, 2500f)), PageBudget(page))

        assertEquals(2, packed.pages.size)
        assertEquals(1, packed.pages[1].slices.size)
        assertEquals(0.4f, packed.pages[1].slices[0].scale, 0.00001f)
        assertEquals(listOf(ScaledBlock(1, 2, 0.4f)), packed.scaled)
        assertEquals(0f, packed.pages[1].slices[0].offsetPx, 0.001f)
        assertEquals(2500f, packed.pages[1].slices[0].heightPx, 0.001f)
        assertEquals(emptyList<Int>(), packed.abandoned)
    }

    @Test
    fun `a heading at the foot of a page turns the page and takes its section with it`() {
        val items = listOf(
            block(0, 880f),
            block(1, 60f, keepWithNext = true),
            block(2, 400f, splittable = true, breakOffsetsPx = listOf(100f, 200f, 300f)),
        )

        assertEquals(listOf(listOf(0), listOf(1, 2)), shape(items))
    }

    @Test
    fun `a heading whose section does fit beneath it stays where it is`() {
        val items = listOf(
            block(0, 600f),
            block(1, 60f, keepWithNext = true),
            block(2, 900f, splittable = true, breakOffsetsPx = listOf(100f, 200f, 300f)),
        )

        assertEquals(listOf(listOf(0, 1, 2), listOf(2)), shape(items))
    }

    @Test
    fun `consecutive headings are kept together with the first line under the last of them`() {
        val items = listOf(
            block(0, 800f),
            block(1, 60f, keepWithNext = true),
            block(2, 50f, keepWithNext = true),
            block(3, 400f, splittable = true, breakOffsetsPx = listOf(100f, 200f, 300f)),
        )

        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), shape(items))
    }

    @Test
    fun `a keep-run that could never fit a page does not turn a page to gain nothing`() {
        val items = listOf(
            block(0, 100f),
            block(1, 900f, keepWithNext = true),
            block(2, 900f),
        )

        assertEquals(listOf(listOf(0, 1), listOf(2)), shape(items))
    }

    @Test
    fun `a declared PAGEBREAK still wins, even with most of the page left`() {
        val items = listOf(
            block(0, 50f),
            block(2, 50f, breakBefore = true),
            block(3, 50f),
        )

        assertEquals(listOf(listOf(0), listOf(2, 3)), shape(items))
    }

    @Test
    fun `a cover is exactly one page, carries nothing else, and gets the taller content box`() {
        val packed = packPages(
            listOf(block(0, 1100f, isCover = true), block(1, 200f)),
            PageBudget(page, 1150f),
        )

        assertEquals(listOf(true, false), packed.pages.map { it.isCover })
        assertEquals(listOf(0), packed.pages[0].slices.map { it.blockIndex })
        assertEquals(emptyList<ScaledBlock>(), packed.scaled)
    }

    @Test
    fun `a callout is moved whole while it fits a page and divided only when it fits none`() {
        val offsets = listOf(200f, 400f, 600f, 800f, 1000f, 1200f)
        val fits = listOf(
            block(0, 500f),
            block(1, 800f, splittable = true, preferWhole = true, breakOffsetsPx = offsets),
        )
        assertEquals(listOf(listOf(0), listOf(1)), shape(fits))

        val doesNot = listOf(
            block(0, 500f),
            block(1, 1400f, splittable = true, preferWhole = true, breakOffsetsPx = offsets),
        )
        assertEquals(listOf(listOf(0, 1), listOf(1)), shape(doesNot))
    }

    @Test
    fun `a splittable block with no legal cut points behaves as an unsplittable one`() {
        val items = listOf(
            block(0, 600f),
            block(1, 600f, splittable = true, breakOffsetsPx = emptyList()),
        )
        assertEquals(listOf(listOf(0), listOf(1)), shape(items))
    }

    @Test
    fun `every block reaches a page, and nothing is abandoned`() {
        val items = listOf(
            block(0, 1100f, isCover = true),
            block(1, 80f, keepWithNext = true),
            block(2, 2400f, splittable = true, breakOffsetsPx = listOf(300f, 700f, 1100f, 1500f, 1900f, 2300f)),
            block(3, 3000f),
            block(4, 120f, breakBefore = true),
            block(5, 700f, splittable = true, preferWhole = true, breakOffsetsPx = listOf(350f)),
            block(6, 260f, gapBeforePx = 18f),
        )
        val packed = packPages(items, PageBudget(page, 1150f))

        val placed = packed.pages.flatMap { p -> p.slices.map { it.blockIndex } }.toSortedSet().toList()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), placed)
        assertEquals(emptyList<Int>(), packed.abandoned)
        for (p in packed.pages) assertTrue(p.usedPx <= p.capacityPx + 0.5f)
    }

    @Test
    fun `a document of nothing but breaks still renders as one sheet`() {
        val packed = packPages(emptyList(), PageBudget(page))
        assertEquals(1, packed.pages.size)
        assertEquals(emptyList<PageSlice>(), packed.pages[0].slices)
    }

    private fun runs(text: String) = listOf(Run(text))

    @Test
    fun `a PAGEBREAK becomes the following block's breakBefore and never a slice of its own`() {
        val blocks = listOf(
            ParagraphBlock(runs("before")),
            PageBreakBlock,
            ParagraphBlock(runs("after")),
        )
        val items = dwPlanFlow(blocks, gapPx = 10f, heightOf = { 100f }, offsetsOf = { emptyList() }, headerOf = { 0f })

        assertEquals(listOf(0, 2), items.map { it.blockIndex })
        assertEquals(false, items[0].breakBefore)
        assertEquals(true, items[1].breakBefore)
    }

    @Test
    fun `a trailing PAGEBREAK produces no blank final sheet`() {
        val blocks = listOf(ParagraphBlock(runs("only")), PageBreakBlock)
        val items = dwPlanFlow(blocks, gapPx = 10f, heightOf = { 100f }, offsetsOf = { emptyList() }, headerOf = { 0f })

        assertEquals(listOf(0), items.map { it.blockIndex })
        assertEquals(1, packPages(items, PageBudget(page)).pages.size)
    }

    @Test
    fun `the gap is charged to every block but the first, because a page break eats it`() {
        val blocks = listOf(ParagraphBlock(runs("a")), ParagraphBlock(runs("b")))
        val items = dwPlanFlow(blocks, gapPx = 10f, heightOf = { 100f }, offsetsOf = { emptyList() }, headerOf = { 0f })

        assertEquals(0f, items[0].gapBeforePx, 0.001f)
        assertEquals(10f, items[1].gapBeforePx, 0.001f)
    }

    @Test
    fun `one unmeasured block abandons the whole plan rather than paginating half a document`() {
        val blocks = listOf(ParagraphBlock(runs("a")), ParagraphBlock(runs("b")))
        val items = dwPlanFlow(
            blocks,
            gapPx = 10f,
            heightOf = { index -> if (index == 0) 100f else null },
            offsetsOf = { emptyList() },
            headerOf = { 0f },
        )
        assertEquals(emptyList<FlowItem>(), items)
    }

    @Test
    fun `each block type carries the rule its FILE writer applies to it`() {
        assertEquals(true, dwBlockRule(CoverBlock(title = "T")).isCover)
        assertEquals(true, dwBlockRule(TocBlock()).breakAfter)
        assertEquals(true, dwBlockRule(HeadingBlock(level = 1, runs = runs("H"))).keepWithNext)
        assertEquals(true, dwBlockRule(ParagraphBlock(runs("p"))).splittable)
        assertEquals(true, dwBlockRule(BulletListBlock(items = listOf(runs("i")))).splittable)
        assertEquals(true, dwBlockRule(KeyValueBlock(pairs = listOf("k" to runs("v")))).splittable)

        val table = TableBlock(
            columns = listOf(TableColumn("A", 50f), TableColumn("B", 50f)),
            rows = listOf(listOf(runs("1"), runs("2"))),
        )
        assertEquals(true, dwBlockRule(table).splittable)
        assertEquals(true, dwBlockRule(table).repeatsHeader)

        val callout = dwBlockRule(CalloutBlock(kind = "INFO", title = "t", runs = runs("c")))
        assertEquals(true, callout.preferWhole)
        assertEquals(true, callout.splittable)

        assertEquals(false, dwBlockRule(MetricRowBlock(metrics = emptyList())).splittable)
        assertEquals(false, dwBlockRule(SignatureBlock(signatories = emptyList())).splittable)
        assertEquals(false, dwBlockRule(SpacerBlock()).splittable)
    }

    @Test
    fun `the paper comes from PageSize and marginMm, at the web's own units per millimetre`() {
        val a4 = dwPageGeometry(PageSize.A4)
        assertEquals(210f * 96f / 25.4f, a4.pageWidthPx, 0.01f)
        assertEquals(297f * 96f / 25.4f, a4.pageHeightPx, 0.01f)
        assertEquals(160f * 96f / 25.4f, a4.textWidthPx, 0.01f)
        assertEquals("A4 · 210 × 297 mm", a4.label)

        val letter = dwPageGeometry(PageSize.LETTER, marginMm = 20f)
        assertEquals(215.9f * 96f / 25.4f, letter.pageWidthPx, 0.01f)
        assertEquals((215.9f - 40f) * 96f / 25.4f, letter.textWidthPx, 0.01f)
        assertEquals("LETTER · 215.9 × 279.4 mm", letter.label)
    }

    @Test
    fun `an impossible margin is clamped rather than trusted into a negative text column`() {
        val silly = dwPageGeometry(PageSize.A4, marginMm = 120f)
        assertTrue(silly.textWidthPx > 0f)
        assertEquals(210f * 0.4f, silly.marginMm, 0.001f)

        assertEquals(0f, dwPageGeometry(PageSize.A4, marginMm = -5f).marginMm, 0.001f)
        assertEquals(DW_DEFAULT_MARGIN_MM, dwPageGeometry(PageSize.A4, marginMm = Float.NaN).marginMm, 0.001f)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "frontend/components/designworkshop/report").isDirectory) return dir
            dir = dir.parentFile
        }
        error("the repository root was not found above ${File("").absolutePath}")
    }

    private fun androidSource(relative: String): String {
        val file = File("src/main/java/com/designprototype/workshop/$relative")
        assertTrue("$relative is not where this test expects it", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun webSource(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("$relative is not where this test expects it", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun `the sheet is a FIXED A4 box on this client too, which is the whole of the visual defect`() {
        val sheet = androidSource("ui/designworkshop/DwReportSheets.kt")
        assertTrue(
            "a page must be a fixed A4 box, never one that wraps its content",
            Regex("""\.size\(width = pageWidth, height = pageHeight\)""").containsMatchIn(sheet),
        )
        assertTrue("the page's body must clip", sheet.contains(".clipToBounds()"))
    }

    @Test
    fun `both clients say the same thing about what the page count is worth`() {
        val sheet = androidSource("ui/designworkshop/DwReportSheets.kt")
        val web = webSource("frontend/components/designworkshop/report/ReportSheet.tsx")

        for (fragment in SHARED_CLAIM_FRAGMENTS) {
            assertTrue("the Android strip is missing: $fragment", sheet.contains(fragment))
            assertTrue("the web strip is missing: $fragment", web.contains(fragment))
        }

        assertTrue("the floor claim must not survive on Android", !sheet.contains("at least this many"))
        assertTrue("the floor claim must not survive on the web", !web.contains("at least this many"))
    }

    companion object {
        val SHARED_CLAIM_FRAGMENTS = listOf(
            "Page counts are close estimates",
            "Word and the .pdf may break lines differently",
        )
    }
}
