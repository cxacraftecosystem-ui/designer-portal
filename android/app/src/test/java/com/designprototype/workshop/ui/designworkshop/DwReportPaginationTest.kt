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

/**
 * THE HANDSET HAD NO PAGE PREVIEW AT ALL, AND THIS IS THE HALF THAT HAS TO AGREE WITH THE WEB.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE SAME CASES AS `frontend/e2e/report-pagination-unit.spec.ts`, IN THE SAME ORDER
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Every test below is the Kotlin twin of a test in that file — same name, same numbers, same
 * assertions — because [packPages] here is a PORT of `reportPagination.ts` and the whole value of a
 * port is that it cannot quietly stop being one. A rule taught to one client and not the other fails
 * here or fails there, and either way somebody is told.
 *
 * WHY THAT MATTERS MORE THAN IT LOOKS. There are four renderers of this document —
 * `backend/app/services/report_docx.py`, `report_pdf.py`, `DocxWriter.kt` and `PdfWriter.kt` — and a
 * ministry receives what they produce. Two PREVIEWS that disagreed about where a page ends would add
 * a fifth and a sixth opinion: a designer approving the layout on a laptop and a colleague checking
 * it on a handset would be looking at two different documents, neither of which is the file. So the
 * packing rule lives once, in one shape, and this file is what holds the two copies of it together.
 *
 * EVERY RULE ASSERTED HERE IS A FILE WRITER'S, NOT A CLIENT'S. The cases are written against
 * `report_pdf.py`'s behaviour and cite it: `place_row` repeating a table header over a continuation,
 * `_block_heading` reserving one body line beneath itself, `_cut_row` refusing to divide a block that
 * would fit a page of its own, `_block_cover` ending with `_new_page()`.
 *
 * The last group are source reads, for the reason the sibling specs give: a composable cannot be
 * mounted in a JVM unit test, and the things they pin — the sheet's height being FIXED rather than a
 * minimum, and the honesty sentence being worded identically on both clients — are exactly the two
 * halves of the defect this change exists to end.
 */
class DwReportPaginationTest {

    private val page = 1000f

    /** A block with the boring answers filled in, so each test states only what it is about. */
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

    /** Which blocks, in order, put a slice on each page. */
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
        // `_new_page` resets the cursor to the top margin; the space that would have sat between two
        // blocks does not travel across the break with the second of them. A gap that DID survive
        // would put an unexplained indent at the top of every continuation page.
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
        // One unit. This is the case a `min-height` sheet answers by growing: the sheet simply
        // becomes 1001 tall and stops depicting a page. The handset had no sheet at all, which is the
        // same failure with the page removed rather than stretched.
        val items = listOf(block(0, 400f), block(1, 601f))
        val packed = packPages(items, PageBudget(page))

        assertEquals(listOf(listOf(0), listOf(1)), shape(items))
        assertTrue(packed.pages[0].usedPx <= page)
        assertTrue(packed.pages[1].usedPx <= page)
    }

    @Test
    fun `half a unit over is not over - sub-unit measurement must not start a page`() {
        // A Compose measure pass divided by the display density is sub-unit, so a block measured at
        // 1000.2 against a 1000 content box is the same block. Treating that as an overflow would
        // break a page for a rounding error, and would do it on every page of the document.
        assertEquals(listOf(listOf(0)), shape(listOf(block(0, 1000.2f))))
    }

    @Test
    fun `a paragraph is divided at its line boxes, and the pieces say which is which`() {
        // `_draw_lines` calls `_ensure(line.height)` once per line, so a paragraph breaks between
        // lines. On this client the offsets come from `TextLayoutResult.getLineBottom`.
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
        // The offsets ARE the line boxes; cutting anywhere else slices a line of type in half
        // horizontally, which is a thing no printer has ever done.
        val packed = packPages(
            listOf(block(0, 900f, splittable = true, breakOffsetsPx = listOf(120f, 380f, 640f))),
            PageBudget(500f),
        )

        assertEquals(380f, packed.pages[0].slices[0].heightPx, 0.001f)
        assertEquals(380f, packed.pages[1].slices[0].offsetPx, 0.001f)
    }

    @Test
    fun `a table repeats its header over every continuation, the way place_row does`() {
        // `place_row` calls `place_header()` after `_new_page()` — "exactly as Word repeats a
        // `<w:tblHeader/>` row over a body row it has split". The header costs room on the
        // continuation page, so the packer has to RESERVE it rather than merely draw it.
        val rows = listOf(80f, 160f, 240f, 320f, 400f, 480f, 560f, 640f, 720f)
        val packed = packPages(
            listOf(block(0, 800f, splittable = true, breakOffsetsPx = rows, repeatHeaderPx = 40f)),
            PageBudget(500f),
        )

        assertEquals(2, packed.pages.size)
        assertEquals(0f, packed.pages[0].slices[0].repeatHeaderPx, 0.001f)
        assertEquals(40f, packed.pages[1].slices[0].repeatHeaderPx, 0.001f)
        // The first page takes rows up to the 480 offset — all 500 of it. The continuation then has
        // 500 less the 40 header, so the header is RESERVED rather than drawn over the rows: 320 of
        // table under 40 of head comes to 360, inside the page, where 320 + 40 charged to a full 500
        // would not have been.
        assertEquals(480f, packed.pages[0].slices[0].heightPx, 0.001f)
        assertEquals(320f, packed.pages[1].slices[0].heightPx, 0.001f)
        assertEquals(360f, packed.pages[1].usedPx, 0.001f)
    }

    @Test
    fun `an unsplittable block taller than a page gets a page of its own and is SCALED, not clipped`() {
        // The .pdf never has this for a picture: it caps a photograph at 0.62 of the text column
        // (`_block_image`) and a chart at 0.58 (`_block_figure`), so it scales too. For anything else
        // the .pdf cuts mid-cell, which neither client can do without measuring inside every cell —
        // so the preview scales and REPORTS it. A silently clipped figure is the one outcome that is
        // not allowed: it is a designer approving a page that does not exist.
        val packed = packPages(listOf(block(0, 200f), block(1, 2500f)), PageBudget(page))

        assertEquals(2, packed.pages.size)
        assertEquals(1, packed.pages[1].slices.size)
        assertEquals(0.4f, packed.pages[1].slices[0].scale, 0.00001f)
        assertEquals(listOf(ScaledBlock(1, 2, 0.4f)), packed.scaled)
        // Nothing is dropped and nothing is left over.
        assertEquals(0f, packed.pages[1].slices[0].offsetPx, 0.001f)
        assertEquals(2500f, packed.pages[1].slices[0].heightPx, 0.001f)
        assertEquals(emptyList<Int>(), packed.abandoned)
    }

    @Test
    fun `a heading at the foot of a page turns the page and takes its section with it`() {
        // `_block_heading` reserves `lead + its own lines + trail + self.base_size * 1.32` — that last
        // term is ONE BODY LINE, and it is the whole of keep-with-next: "a heading with nothing under
        // it is still an orphan even if it fits". So a heading that FITS the remaining room and whose
        // first following line does not must still move.
        val items = listOf(
            block(0, 880f),
            block(1, 60f, keepWithNext = true), // fits the last 120 on its own
            block(2, 400f, splittable = true, breakOffsetsPx = listOf(100f, 200f, 300f)),
        )

        assertEquals(listOf(listOf(0), listOf(1, 2)), shape(items))
    }

    @Test
    fun `a heading whose section does fit beneath it stays where it is`() {
        // The mirror of the case above, so the rule is not "a heading always turns the page".
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

        // 60 + 50 + 100 = 210 does not fit in the 200 left, so the whole run moves rather than the
        // second heading being stranded under the first.
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), shape(items))
    }

    @Test
    fun `a keep-run that could never fit a page does not turn a page to gain nothing`() {
        // The clamp in `keepRunHeight`. `_block_table` paid for the opposite reading once: reserving
        // a header plus an over-tall first row "turned a page the row was going to start on anyway:
        // the table's own first page came out completely blank".
        val items = listOf(
            block(0, 100f),
            block(1, 900f, keepWithNext = true),
            block(2, 900f),
        )

        assertEquals(listOf(listOf(0, 1), listOf(2)), shape(items))
    }

    @Test
    fun `a declared PAGEBREAK still wins, even with most of the page left`() {
        // A break the template asked for is a break in the FILE — every writer honours it exactly —
        // so it outranks every measurement here. `dwPlanFlow` folds the `PageBreakBlock` itself onto
        // the following block as `breakBefore`, which is the form asserted (block 1 is the break, and
        // never appears as a slice).
        val items = listOf(
            block(0, 50f),
            block(2, 50f, breakBefore = true),
            block(3, 50f),
        )

        assertEquals(listOf(listOf(0), listOf(2, 3)), shape(items))
    }

    @Test
    fun `a cover is exactly one page, carries nothing else, and gets the taller content box`() {
        // `_block_cover` ends with `self._new_page()` and no writer draws furniture on page one —
        // which is why the cover's content box is the whole sheet less its margins and an ordinary
        // page's is that less the running head and foot.
        val packed = packPages(
            listOf(block(0, 1100f, isCover = true), block(1, 200f)),
            PageBudget(page, 1150f),
        )

        assertEquals(listOf(true, false), packed.pages.map { it.isCover })
        assertEquals(listOf(0), packed.pages[0].slices.map { it.blockIndex })
        // 1100 fits the cover's 1150 and would NOT have fitted an ordinary page's 1000.
        assertEquals(emptyList<ScaledBlock>(), packed.scaled)
    }

    @Test
    fun `a callout is moved whole while it fits a page and divided only when it fits none`() {
        // One `_cut_row` over the whole flow, whose docstring calls the fits-a-page case "an ordinary
        // break": the block is not cut, it is moved. Cutting a callout that would have sat happily on
        // the next page splits a boxed aside across a page boundary for no reason.
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
        // Too tall for ANY page, so `preferWhole` stops applying and the block is divided at its own
        // legal offsets — starting in the 500 left on this page rather than wasting them. That is
        // `_cut_row` exactly: it returns "an ordinary break" only for `height <= one page`, and
        // otherwise cuts against `budget = self.y - self.bottom - padding`, which is the room left.
        assertEquals(listOf(listOf(0, 1), listOf(1)), shape(doesNot))
    }

    @Test
    fun `a splittable block with no legal cut points behaves as an unsplittable one`() {
        // "The measurer could not find a place to cut this" and "this may not be cut" have to produce
        // the same drawing, or a block would be sliced at an arbitrary unit because a probe missed.
        val items = listOf(
            block(0, 600f),
            block(1, 600f, splittable = true, breakOffsetsPx = emptyList()),
        )
        assertEquals(listOf(listOf(0), listOf(1)), shape(items))
    }

    @Test
    fun `every block reaches a page, and nothing is abandoned`() {
        // The property that matters most, over a document with one of everything in it: a block that
        // silently failed to be placed is a section missing from a document somebody already
        // approved.
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
        // An empty sheet reads as "this template produced nothing", which is the truth; zero sheets
        // would render as an empty panel indistinguishable from a load that had not finished.
        val packed = packPages(emptyList(), PageBudget(page))
        assertEquals(1, packed.pages.size)
        assertEquals(emptyList<PageSlice>(), packed.pages[0].slices)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The plan — the block types' own rules, and what happens to a PAGEBREAK
     * ──────────────────────────────────────────────────────────────────────────── */

    private fun runs(text: String) = listOf(Run(text))

    @Test
    fun `a PAGEBREAK becomes the following block's breakBefore and never a slice of its own`() {
        // The block itself must not reach [packPages]: it has no height, nothing to draw and no
        // place on a page. It IS the page turn, so it folds onto whatever comes after it — which is
        // the same shape the web's `planFlow` produces, and is what the packer's own
        // "declared PAGEBREAK still wins" case above is written against.
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
        // `_new_page()` in the .pdf would emit one. No template in `ReportTemplates.kt` ends with a
        // break, and a blank sheet at the end of a preview reads as a fault in the preview rather
        // than as a fact about the document — the same decision the web's `planFlow` carries.
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
        // A page count built from a partial measurement shuffles as the last photograph decodes, and
        // a reader shown "12 pages" and then "14" has been told a number twice.
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
        // Read off `report_pdf.py` and its on-device twin `PdfWriter.kt`, and asserted here because
        // an exhaustive `when` guarantees every type is HANDLED and guarantees nothing about
        // whether it is handled correctly.
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
        // The header is REDRAWN over a continuation and therefore has to be RESERVED on it.
        assertEquals(true, dwBlockRule(table).repeatsHeader)

        // `_cut_row` refuses to divide a callout that would fit a page of its own.
        val callout = dwBlockRule(CalloutBlock(kind = "INFO", title = "t", runs = runs("c")))
        assertEquals(true, callout.preferWhole)
        assertEquals(true, callout.splittable)

        // Never divided: the whole box is reserved, or the picture is scaled to fit.
        assertEquals(false, dwBlockRule(MetricRowBlock(metrics = emptyList())).splittable)
        assertEquals(false, dwBlockRule(SignatureBlock(signatories = emptyList())).splittable)
        assertEquals(false, dwBlockRule(SpacerBlock()).splittable)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The geometry — the payload's, never this client's
     * ──────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `the paper comes from PageSize and marginMm, at the web's own units per millimetre`() {
        // `report_pdf.py:509-512` reads exactly `page_size.size_mm` and `margin_mm` to size its
        // canvas, and `previewModel.ts` converts with `96 / 25.4`. Both clients must land on the same
        // numbers or a block that fills the column on one wraps onto a second line on the other.
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
        // A template asking for 120 mm margins on A4 would otherwise produce a negative column, and
        // every length downstream — line wrap, page capacity, the packer's own arithmetic — would be
        // computed from it. There is no honest layout past that point, so the value is capped where
        // a fifth of the paper is still printable.
        val silly = dwPageGeometry(PageSize.A4, marginMm = 120f)
        assertTrue(silly.textWidthPx > 0f)
        assertEquals(210f * 0.4f, silly.marginMm, 0.001f)

        assertEquals(0f, dwPageGeometry(PageSize.A4, marginMm = -5f).marginMm, 0.001f)
        assertEquals(DW_DEFAULT_MARGIN_MM, dwPageGeometry(PageSize.A4, marginMm = Float.NaN).marginMm, 0.001f)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The source reads
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The repository root, found by walking up from the module directory the way
     * `DwTextFormatParityTest.tableFile` does. Gradle runs a unit test with `android/app` as its
     * working directory, so the web client is two levels up and one across.
     */
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
        // The web's defect was one word — `min-height` on `.rp-sheet`, which let a sheet grow past
        // the paper it was depicting, so the screen whose entire job is to answer "does this fit on
        // the page?" answered it by making the page bigger. This client's version of the same mistake
        // would be a page box that wraps its content, so the assertion is on the modifier that makes
        // the box a fixed one and on the clip that stops an arithmetic error pushing it open again.
        val sheet = androidSource("ui/designworkshop/DwReportSheets.kt")
        assertTrue(
            "a page must be a fixed A4 box, never one that wraps its content",
            Regex("""\.size\(width = pageWidth, height = pageHeight\)""").containsMatchIn(sheet),
        )
        assertTrue("the page's body must clip", sheet.contains(".clipToBounds()"))
    }

    @Test
    fun `both clients say the same thing about what the page count is worth`() {
        // ANDROID OWNS WORDING IN THIS REPOSITORY, so this file is the one that pins it — and it pins
        // it on BOTH clients at once. The count was a FLOOR while only declared breaks were honoured;
        // it is not a floor now and it is not the file's own count either. It is measured with this
        // device's fonts, while the .pdf is laid out by the writer's own face and the .docx is
        // paginated by Word when the file is opened. Making the preview trustworthy raises the cost
        // of overclaiming rather than removing it, so the sentence has to be exact — and the same
        // sentence in both places, or a designer comparing the two clients is told two things.
        val sheet = androidSource("ui/designworkshop/DwReportSheets.kt")
        val web = webSource("frontend/components/designworkshop/report/ReportSheet.tsx")

        for (fragment in SHARED_CLAIM_FRAGMENTS) {
            assertTrue("the Android strip is missing: $fragment", sheet.contains(fragment))
            assertTrue("the web strip is missing: $fragment", web.contains(fragment))
        }

        // The claim it replaced must not come back beside it. "A floor" was true only while nothing
        // but declared breaks was honoured; a stale floor claim now reads as a promise that the file
        // has AT LEAST this many pages, which is false in both directions.
        assertTrue("the floor claim must not survive on Android", !sheet.contains("at least this many"))
        assertTrue("the floor claim must not survive on the web", !web.contains("at least this many"))
    }

    companion object {
        /**
         * The parts of the honesty sentence that must be identical on both clients, character for
         * character.
         *
         * FRAGMENTS RATHER THAN THE WHOLE SENTENCE, and the reason is worth writing down: the web
         * builds its copy inside JSX, so the string in that file is broken by line wraps, by
         * `&ldquo;`/`&rsquo;` entities and by a `{sheets.length}` interpolation. Asserting the whole
         * sentence would compare a rendered sentence against a source one and fail for a reason that
         * has nothing to do with what either screen says. These three fragments carry the whole of
         * the claim between them: WHERE the measurement happened, WHAT can move a break, and WHAT the
         * reader should therefore treat the number as.
         */
        val SHARED_CLAIM_FRAGMENTS = listOf(
            "a line that wraps differently in Word or in the",
            "can move a break",
            "close estimate of the file",
        )
    }
}
