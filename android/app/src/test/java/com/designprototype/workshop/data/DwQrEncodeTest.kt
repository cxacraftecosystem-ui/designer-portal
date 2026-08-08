package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DwQrEncode] against the two things a hand-written QR encoder has to agree with, PINNED BY VALUE.
 *
 * WHY A GOLDEN AND NOT A LIST OF PROPERTIES. Every property this module has — "the finders are in
 * three corners", "the matrix is square", "a long string is refused" — is one an encoder can satisfy
 * while producing a symbol that scans to nothing, or scans to the right thing only in a browser. The
 * bar is byte-for-byte identity with the web, because a designer prints a sheet of artisan cards from
 * a laptop in the morning and a replacement tag from a handset in the courtyard that afternoon, and
 * the two cards carry the same record. So this file recomputes fixed vectors and DIFFS them.
 *
 * THERE ARE TWO SOURCES, AND THEY ARE DIFFERENT KINDS OF EVIDENCE:
 *
 *  1. `REFERENCE_CASES.masks` is every module of all eight masks of ten symbols, produced by
 *     `reportlab.graphics.barcode.qrencoder`. That is a SECOND IMPLEMENTATION, in a third language,
 *     by different people, from the same ISO 18004. It is what makes this a check and not a
 *     restatement: a misread of the mode header, the character count, the padding, the
 *     Reed-Solomon, the block interleaving, the function patterns, the data placement, the mask
 *     patterns or the format information moves modules here and shows up as a diff.
 *
 *     SEVEN OF THE TEN ARE `frontend/e2e/fixtures/qr-reference.json` VERBATIM — every line can be
 *     grepped for in that file unchanged, and `frontend/e2e/workshop-codes.spec.ts` asserts the
 *     same strings against the TypeScript, so all three implementations are pinned to one document.
 *     The other three (5-Q, 5-H and 6-H) are the same oracle run over three more cases, because the
 *     seven leave a hole this port could have fallen straight into: 5-Q and 5-H are the ONLY rows
 *     in this app's range whose codewords are cut into blocks of two different sizes — where a
 *     naive interleaver drops or repeats a codeword — and no case in the fixture has more than two
 *     blocks, so four-block interleaving (6-H) went unexercised as well.
 *
 *  2. Everything else — the penalty scores, the mask [DwQrEncode.encode] settles on, the refusal
 *     sentences, the SVG paths — is the WEB'S OWN ANSWER, read out of `frontend/lib/qrEncode.ts` by
 *     running it. reportlab cannot supply these: `qr_oracle.py` deliberately dumps all eight masks
 *     and pins none of them as the winner, because reportlab's penalty scorer departs from the
 *     standard in two places (its vertical rule-3 pass compares a tuple against a list and so never
 *     fires, and it looks only for the dark-first finder-lookalike). Mask SELECTION is therefore the
 *     one part of the encoder with no independent oracle, and it is the part that decides which of
 *     eight legal symbols gets printed — so it is pinned against the browser directly, score by
 *     score rather than only by which score won. Two compensating errors in different penalty rules
 *     can agree on an argmin; they cannot agree on fifty-six scores.
 *
 * WHEN THIS FILE AND THE WEB DISAGREE, THE WEB IS RIGHT and this port is broken. Regenerating a
 * golden here until it matches the Kotlin would be regenerating the cards already glued to prototypes
 * in a workshop.
 *
 * THE GOLDEN IS ITSELF TESTED, by breaking each guard in the module in turn — in a copy of the
 * TypeScript, so the mutation is measured against the same frozen numbers this file holds — and
 * counting what moves. A guard nothing can break is a guard nobody can trust, and two of these
 * findings changed what is in this file:
 *
 *  * Scoring only the dark-first finder-lookalike in rule 3, which is reportlab's own departure from
 *    the standard: 59 of the 80 penalty scores move and 21 of the 58 chosen symbols do.
 *  * Interleaving only as far as group 1's length, which drops group 2's last codeword: 16 of the 80
 *    matrices move — and all 16 are the 5-Q and 5-H cases. The seven-case fixture on its own catches
 *    NOTHING here, which is why the three extra cases were generated.
 *  * `<=` instead of `<` when a mask ties the best score: 1 of the 58 chosen symbols moves. Masks do
 *    tie — the closest gap between the best two, over 12,000 generated payloads, was zero.
 *  * Integer division in rule 4, the trap this port was most likely to fall into, moves NOTHING in
 *    this table: the two spellings differ on about one mask in ninety-six thousand and never changed
 *    a winner in that sample. It is said here plainly so no reader assumes the golden covers it; the
 *    argument for the `.0` lives in [DwQrEncode.penalty], where it is made.
 *  * Zeroing the remainder-bit table moves nothing either, and for a reason worth writing down: a
 *    remainder bit is left light by the placement whether it is written or skipped, and the mask
 *    inverts it identically afterwards. The constant records the standard's intent; the data
 *    placement already satisfies it.
 *
 * THE CASES ARE CHOSEN FOR WHERE AN ENCODER GOES WRONG, not for coverage of the happy path:
 *
 *  * `HELLO WORLD` at 1-Q is the standard's own worked example, so a total misreading of the mode
 *    encoding shows up in the one string every QR reference in print agrees about.
 *  * 5-Q and 5-H are the two-block-size rows and 6-H is four equal blocks, for the reason above;
 *    6-L is the largest symbol this app will draw.
 *  * `CHOSEN` holds BOTH SIDES of all twenty version boundaries — a string of exactly
 *    `capacity(v, level)` characters and one of `capacity(v - 1, level) + 1` — so an off-by-one in
 *    [DwQrEncode.capacity] cannot pass as "fits", in either direction, at any level.
 *  * A no-break space and an astral emoji, because the alphanumeric test walks UTF-16 code units
 *    here and code points in the browser, and the two agree only by a property of this alphabet.
 */
class DwQrEncodeTest {

    /** `matrix[row][column]` rendered the way `qr-reference.json` renders it, so the two compare directly. */
    private fun render(matrix: Array<BooleanArray>): String =
        matrix.joinToString("/") { row -> row.joinToString("") { if (it) "1" else "0" } }

    /* ────────────────────────────────────────────────────────────────────────
     * The symbol, module by module
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * Every module of every mask of every case, against reportlab.
     *
     * All mismatches are collected before failing. "Mask 5 of case 3 differs" is a finding somebody
     * can act on; a stop at the first difference hides whether one rule is wrong or the whole
     * codeword stream is, which is the difference between an afternoon and a week.
     */
    @Test
    fun `every module of every mask matches an independent encoder`() {
        assertTrue("the reference fixture did not load", REFERENCE_CASES.size > 3)
        val mismatches = ArrayList<String>()
        for (case in REFERENCE_CASES) {
            for (mask in 0 until 8) {
                val mine = render(DwQrEncode.buildMatrix(case.text, case.version, case.level, mask))
                if (mine != case.masks[mask]) {
                    mismatches += "version ${case.version}${case.level}, mask $mask, \"${case.text}\"" +
                        firstDifference(mine, case.masks[mask])
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /** Where two rendered matrices part company, as a row and column a reader can go and look at. */
    private fun firstDifference(mine: String, theirs: String): String {
        if (mine.length != theirs.length) return " (${mine.length} characters against ${theirs.length})"
        val at = mine.indices.firstOrNull { mine[it] != theirs[it] } ?: return ""
        val rows = mine.substring(0, at).count { it == '/' }
        val column = at - mine.substring(0, at).lastIndexOf('/') - 1
        return " (first at row $rows column $column: ${mine[at]} against ${theirs[at]})"
    }

    /**
     * The symbol [DwQrEncode.encode] actually returns, drawn out in full for one case.
     *
     * The test above drives [DwQrEncode.buildMatrix] with an explicit mask, so on its own it would
     * pass even if [DwQrEncode.encode] returned the wrong candidate's matrix alongside the right
     * mask number — a mix-up that no assertion about `mask` alone can see.
     */
    @Test
    fun `the matrix encode returns is the one its own mask number describes`() {
        val symbol = DwQrEncode.encode(ENCODED_MATRIX_TEXT, ENCODED_MATRIX_LEVEL)
        assertEquals(ENCODED_MATRIX, render(symbol.matrix))
        assertEquals(
            render(DwQrEncode.buildMatrix(ENCODED_MATRIX_TEXT, symbol.version, symbol.level, symbol.mask)),
            render(symbol.matrix),
        )
    }

    /* ────────────────────────────────────────────────────────────────────────
     * The tables the symbol is built out of
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * Every row of the block table, checked by arithmetic against ISO 18004 table 9.
     *
     * A transcription slip in that table produces a symbol that is subtly unreadable rather than
     * obviously wrong: the blocks still interleave, the matrix still fills, and a scanner simply
     * fails to correct an error it should have corrected. The arithmetic is the cheap way to catch
     * it — data plus error correction, over every block, must be the version's total codewords.
     */
    @Test
    fun `the block table is internally consistent with the codeword totals`() {
        for (level in DwQrEccLevel.entries) {
            for (version in DwQrEncode.MIN_VERSION..DwQrEncode.MAX_VERSION) {
                val (ec, g1Blocks, g1Data, g2Blocks, g2Data) = DwQrEncode.blocks(version, level)
                assertEquals(
                    "$level$version codewords",
                    DwQrEncode.totalCodewords(version),
                    g1Blocks * (g1Data + ec) + g2Blocks * (g2Data + ec),
                )
                // Where the standard splits a version into two block sizes, group 2's data count is
                // always exactly one more than group 1's. A row that broke that rule would still
                // satisfy the sum above while interleaving in the wrong order.
                if (g2Blocks > 0) assertEquals("$level$version group 2", g1Data + 1, g2Data)
            }
        }
    }

    /**
     * The alphanumeric capacities, against the standard's published figures.
     *
     * [DwQrEncode.capacity] derives these from the block table rather than tabulating them, so this
     * is also the second, independent check on that table: a wrong data-codeword count moves a
     * capacity even when the codeword total still adds up.
     */
    @Test
    fun `the capacity table is the standard's published alphanumeric figures`() {
        for ((key, expected) in CAPACITY) {
            val level = DwQrEccLevel.valueOf(key.substring(0, 1))
            val version = key.substring(1).toInt()
            assertEquals(key, expected, DwQrEncode.capacity(version, level))
        }
        assertEquals("every version at every level", 24, CAPACITY.size)
    }

    /* ────────────────────────────────────────────────────────────────────────
     * Mask selection — the part reportlab cannot check
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * All four penalty rules, read out score by score against the browser's.
     *
     * This is the test that catches the trap this port was most likely to fall into: rule 4 divides
     * `dark * 100` by `size * size`, which is FLOAT division in JavaScript and INTEGER division in
     * Kotlin. Written the obvious way the truncated percentage moves the term by ten points on a
     * good fraction of these matrices, a different mask wins, and the handset prints a legal symbol
     * that is not the one on the card in the workshop. Nothing else in the suite would notice: the
     * module-by-module test drives the mask explicitly, and every mask is a valid symbol.
     */
    @Test
    fun `the penalty scorer agrees with the browser, score for score`() {
        val mismatches = ArrayList<String>()
        for (case in REFERENCE_CASES) {
            for (mask in 0 until 8) {
                val mine = DwQrEncode.penalty(DwQrEncode.buildMatrix(case.text, case.version, case.level, mask))
                if (mine != case.scores[mask]) {
                    mismatches += "${case.version}${case.level} mask $mask: $mine against ${case.scores[mask]}"
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /**
     * The version, the size and the mask [DwQrEncode.encode] chooses, for the strings this app prints
     * and for the ones either side of every capacity boundary.
     */
    @Test
    fun `encode picks the version, size and mask the browser picks`() {
        val mismatches = ArrayList<String>()
        for (case in CHOSEN) {
            val symbol = DwQrEncode.encode(case.text, case.level)
            val mine = "v${symbol.version} size ${symbol.size} mask ${symbol.mask}"
            val theirs = "v${case.version} size ${case.size} mask ${case.mask}"
            if (mine != theirs) mismatches += "${case.level} \"${abbreviate(case.text)}\": $mine against $theirs"
            assertEquals("matrix side", symbol.size, symbol.matrix.size)
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun abbreviate(text: String): String =
        if (text.length <= 40) text else text.take(20) + "…(${text.length})"

    /**
     * The three corner finders, on the symbol a real artisan card carries.
     *
     * The cheapest structural proof that a matrix is a symbol rather than noise, kept from the web's
     * spec because it is the assertion that stays readable when the golden above fails: a diff of
     * eight hundred modules does not tell a reader whether the drawing collapsed or one codeword
     * moved, and this does.
     */
    @Test
    fun `the symbol picked for a real code is the smallest that fits, at the level printed cards need`() {
        val symbol = DwQrEncode.encode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", DwQrEccLevel.Q)
        assertEquals(3, symbol.version)
        assertEquals(29, symbol.size)
        assertEquals(29, symbol.matrix.size)
        for ((row, column) in listOf(0 to 0, 0 to 22, 22 to 0)) {
            assertTrue("finder at $row,$column", symbol.matrix[row][column])
            assertTrue("finder ring at $row,$column", !symbol.matrix[row + 1][column + 1])
            assertTrue("finder centre at $row,$column", symbol.matrix[row + 3][column + 3])
        }

        // An offline prototype's UUID client key needs a bigger symbol, and the web sheet's 26mm box
        // is sized for exactly this one. If this ever exceeds version 4, that box must grow with it —
        // on both clients, which is why the figure is asserted here as well as there.
        val prototype = DwQrEncode.encode("DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER", DwQrEccLevel.Q)
        assertEquals(4, prototype.version)
    }

    /* ────────────────────────────────────────────────────────────────────────
     * Refusals
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * A string the encoder cannot carry is refused, never truncated — with the browser's sentence.
     *
     * A truncated identifier scans cleanly and resolves to the wrong record or to none, silently, and
     * that single failure is why this whole feature exists. The MESSAGE is pinned as well as the
     * reason because a designer holding a card that will not print is told this sentence, and being
     * told two different sentences by the laptop and the handset about one code reads as two
     * different faults.
     */
    @Test
    fun `a string the encoder cannot carry is refused, never truncated`() {
        val mismatches = ArrayList<String>()
        for (case in REFUSALS) {
            val outcome = try {
                DwQrEncode.encode(case.text, case.level)
                "NONE|"
            } catch (error: DwQrEncodeException) {
                "${error.reason}|${error.message}"
            }
            val expected = "${case.reason}|${case.message}"
            if (outcome != expected) mismatches += "\"${abbreviate(case.text)}\" at ${case.level}: $outcome"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /**
     * Which characters alphanumeric mode carries, including the ones that look as though it should.
     *
     * The no-break space is the case worth having: it is what a paste out of a spreadsheet leaves
     * behind, it is invisible on screen, and it is NOT the space in the forty-five. The astral emoji
     * and the lone surrogate are here because the browser iterates code points and this iterates
     * UTF-16 code units — the answers coincide only because every character in the set is ASCII, and
     * a case that would notice if somebody widened the set is worth more than the comment saying so.
     */
    @Test
    fun `only the forty-five characters alphanumeric mode carries are accepted`() {
        val mismatches = ArrayList<String>()
        for (case in ALPHANUMERIC) {
            val mine = DwQrEncode.isAlphanumeric(case.text)
            if (mine != case.ok) mismatches += "${describe(case.text)}: $mine against ${case.ok}"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /**
     * A string named by its code units, because the interesting ones are invisible.
     *
     * Spelled out with `toString(16)` rather than `"U+%04X".format(…)` for the reason the module
     * under test spells its SVG path out: `format` without an explicit locale is
     * `Locale.getDefault`, and a failure message in Odia digits on the one handset that reproduces
     * the bug is a failure message nobody can search for.
     */
    private fun describe(text: String): String =
        text.map { "U+" + it.code.toString(16).uppercase().padStart(4, '0') }
            .joinToString(" ")
            .ifEmpty { "(empty)" }

    /* ────────────────────────────────────────────────────────────────────────
     * The SVG path
     * ──────────────────────────────────────────────────────────────────────── */

    /**
     * The path string itself, character for character against the browser's.
     *
     * Pinned as a STRING and not only replayed into a grid, because the two clients hand this to the
     * same printer: a path that draws the same modules with different command spelling would replay
     * identically and still make the two sheets differ, and one of the two would be the sheet a
     * scanner hesitates over. `M12 5h3v1h-3z` — no separators, no decimal points, ASCII digits
     * whatever the handset's locale is set to.
     */
    @Test
    fun `the SVG path is the browser's, character for character`() {
        for (case in SVG) {
            val svg = DwQrEncode.svgPath(DwQrEncode.encode(case.text, case.level), case.quiet)
            assertEquals("extent for \"${case.text}\" quiet ${case.quiet}", case.extent, svg.extent)
            assertEquals("path for \"${case.text}\" quiet ${case.quiet}", case.path, svg.path)
        }
    }

    /**
     * The path replayed back into a grid and compared with the matrix it came from.
     *
     * A path that drew one module short, or one module offset, is invisible on screen and unreadable
     * on paper. This is the web spec's own assertion, kept because it checks something the frozen
     * string above cannot: that the string means what the matrix says, rather than that two
     * implementations produce the same wrong string.
     */
    @Test
    fun `the SVG path covers exactly the dark modules, offset by the quiet zone`() {
        val symbol = DwQrEncode.encode("DPW1:P:ABCDEFGH:0000", DwQrEccLevel.M)
        val svg = DwQrEncode.svgPath(symbol, 4)
        assertEquals(symbol.size + 8, svg.extent)

        val command = Regex("""M(\d+) (\d+)h(\d+)v1h-\3z""")
        val replay = Array(svg.extent) { BooleanArray(svg.extent) }
        var drawn = 0
        for (match in command.findAll(svg.path)) {
            val (column, row, run) = match.destructured.toList().map { it.toInt() }
            for (offset in 0 until run) replay[row][column + offset] = true
            drawn += run
        }
        // Nothing in the path that the regular expression above did not understand — a path with an
        // unparsed command would compare equal here while drawing something else on paper.
        assertEquals("", command.replace(svg.path, ""))
        assertTrue("only $drawn modules drawn", drawn > 100)

        for (row in 0 until svg.extent) {
            for (column in 0 until svg.extent) {
                val inside = row >= 4 && column >= 4 && row < 4 + symbol.size && column < 4 + symbol.size
                assertEquals(
                    "$row,$column",
                    if (inside) symbol.matrix[row - 4][column - 4] else false,
                    replay[row][column],
                )
            }
        }
    }

    /* ────────────────────────────────────────────────────────────────────────
     * The frozen vectors
     *
     * `masks` is `frontend/e2e/fixtures/qr-reference.json` verbatim — regenerate with
     *
     *     backend/.venv/Scripts/python.exe scripts/qr_oracle.py > frontend/e2e/fixtures/qr-reference.json
     *
     * and copy the strings across unchanged; any line here can be grepped for in that file. Every
     * other number and string below is the browser's answer, read out of `frontend/lib/qrEncode.ts`
     * by running it over these same inputs.
     * ──────────────────────────────────────────────────────────────────────── */

    private class ReferenceCase(
        val text: String,
        val version: Int,
        val level: DwQrEccLevel,
        /** All eight masks, from reportlab. */
        val masks: List<String>,
        /** The four penalty rules summed, per mask, from the browser. */
        val scores: List<Int>,
    )

    private class ChosenCase(
        val text: String,
        val level: DwQrEccLevel,
        val version: Int,
        val size: Int,
        val mask: Int,
    )

    private class RefusalCase(
        val text: String,
        val level: DwQrEccLevel,
        val reason: String,
        val message: String,
    )

    private class AlphanumericCase(val text: String, val ok: Boolean)

    private class SvgCase(val text: String, val level: DwQrEccLevel, val quiet: Int, val extent: Int, val path: String)

    private val REFERENCE_CASES = listOf(
        ReferenceCase(
            text = "HELLO WORLD",
            version = 1,
            level = DwQrEccLevel.Q,
            masks = listOf(
                "111111101100001111111/100000101001001000001/101110101001101011101/101110101000001011101/101110101010001011101/100000100010001000001/111111101010101111111/000000001000000000000/011010110000101011111/010000001111000010001/001101110110001011000/011011010011010101110/100010101011101110101/000000001101001000101/111111101010000101100/100000100101101101000/101110101010001111111/101110100101010100010/101110101001011101001/100000101011110001011/111111100001011100001",
                "111111100001001111111/100000100100001000001/101110100100101011101/101110101101001011101/101110100111001011101/100000101111001000001/111111101010101111111/000000001101000000000/011000100101101101000/000101011010010111011/011000100011011110010/001110000110000000100/110111111110111011111/000000001000011101111/111111100111010000110/100000100000111000010/101110100111011010101/101110100000000001000/101110101100001000011/100000101110100100001/111111100100001001011",
                "111111101010001111111/100000100000101000001/101110100111101011101/101110100001101011101/101110101100001011101/100000101011101000001/111111101010101111111/000000000001100000000/011111110110100110001/100001011110110011111/000011111000000101001/101010000010100100000/101100100101100000100/000000001100111001011/111111101100001011101/100000101100011100110/101110101100000001110/101110101100100101100/101110101111010011000/100000101010000000101/111111100111010010000",
                "111111100010001111111/100000101101001000001/101110101001001011101/101110100001101011101/101110100001101011101/100000100101001000001/111111101010101111111/000000000100000000000/011101100000000000110/100001011110110011111/101110110101101000100/011100010100010010110/101100100101100000100/000000001001010100110/111111100010111101011/100000101100011100110/101110100001101100011/101110101010010011010/101110101111010011000/100000101111101101000/111111100001100100110",
                "111111100110001111111/100000100100101000001/101110101100001011101/101110100010001011101/101110101000001011101/100000101111101000001/111111101010101111111/000000000010000000000/010010101010110110100/111101000010101111100/100000111011100110101/001001000001000111100/110000111001111100111/000000001000100101000/111111100111101000001/100000100111111111010/101110101000011101101/101110100000111001111/101110100100110000100/100000101001100011001/111111100011001110011",
                "111111101001001111111/100000101100101000001/101110100111101011101/101110100111101011101/101110100100001011101/100000100111101000001/111111101010101111111/000000000101100000000/010000111110110000011/101111010000111101110/000011111000000101001/101110000110100000000/110111111110111011111/000000001000111101011/111111101100001011101/100000100010010010111/101110100100000001110/101110100000100001100/101110100100001000011/100000101110000100101/111111100111010010000",
                "111111100001001111111/100000101100101000001/101110100101101011101/101110101111101011101/101110101101001011101/100000100100101000001/111111101010101111111/000000001101100000000/010111101100111011010/101111010000111101110/001010110001001100000/101101000101100011000/110111111110111011111/000000001000100101000/111111100110011001111/100000101010010010111/101110101101001000111/101110101011100010100/101110100100001000011/100000101110011100110/111111100101000000010",
                "111111101100001111111/100000100011001000001/101110101000101011101/101110101000001011101/101110100000001011101/100000101011001000001/111111101010101111111/000000001010000000000/010101111001111101101/010000001111000010001/011111100100011001010/010010011010011100111/100010101011101110101/000000001111011010111/111111101011001100101/100000101101101101000/101110100000011101101/101110101100011101011/101110100001011101001/100000101001100011001/111111100000010101000",
            ),
            scores = listOf(347, 470, 506, 441, 539, 516, 314, 558),
        ),
        ReferenceCase(
            text = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD",
            version = 3,
            level = DwQrEccLevel.Q,
            masks = listOf(
                "11111110100000100110001111111/10000010111010101110001000001/10111010101100001011001011101/10111010111000101100001011101/10111010110110010011001011101/10000010011001000101101000001/11111110101010101010101111111/00000000111111101111100000000/01101011010111100010101011111/11010000110001010111001011011/11001111001111110010101100001/10110000010011000101001110011/01100111101110001011010010111/00100000111001001000001001110/00110010010100011101010001011/01001001001101101001000010010/10110010000101100000101110100/01001001010100110100101100011/10011010011100100010100011000/01101100010110110101111100111/10011111100010001011111110101/00000000110001001111100011010/11111110111100100010101011010/10000010010000010110100010111/10111010101001101011111111010/10111010010001011010100010000/10111010100101011001100000101/10000010101101101001100000011/11111110001001011000111011101",
                "11111110010101110011001111111/10000010001111111011001000001/10111010011001011110001011101/10111010101101111001001011101/10111010000011000110001011101/10000010101100010000101000001/11111110101010101010101111111/00000000101010111010100000000/01100010000010110111101101000/10000101100100000010011110001/10011010011010100111111001011/11100101000110010000011011001/00110010111011011110000111101/01110101101100011101011100100/01100111000001001000000100001/00011100011000111100010111000/11100111010000110101111011110/00011100000001100001111001001/11001111001001110111110110010/00111001000011100000101001101/11001010110111011110111111111/00000000100100011010100010000/11111110001001110111101010000/10000010000101000011100011101/10111010011100111110111110000/10111010000100001111110111010/10111010110000001100110101111/10000010111000111100110101001/11111110011100001101101110111",
                "11111110111000011110101111111/10000010011101101001001000001/10111010010100110011101011101/10111010011111101011001011101/10111010101110101011101011101/10000010111110000010101000001/11111110101010101010101111111/00000000011000101000100000000/01111111001111011010000110001/00010101110110010000001100011/11110111110111001010010100110/01110101010100000010001001011/01011111010110110011101010000/11100101111110001111001110110/00001010101100100101101001100/10001100001010101110000101010/10001010111101011000010110011/10001100010011110011101011011/10100010100100011010011011111/10101001010001110010111011111/10100111011010110011111110010/00000000110110001000100010010/11111110100100011010101011101/10000010110111010001100011111/10111010110001010011111111101/10111010110110011101100101000/10111010111101100001011000010/10000010101010101110100111011/11111110010001100000000011010",
                "11111110011000011110101111111/10000010101011011111101000001/10111010101111101000101011101/10111010011111101011001011101/10111010011000011101001011101/10000010000101011001101000001/11111110101010101010101111111/00000000001110011110000000000/01110110010100000001000000110/00010101110110010000001100011/01000011000001111100100010000/10101100001111011001010010000/01011111010110110011101010000/01010001001000111001111000000/11010011110111111110110010111/10001100001010101110000101010/00111110001011101110100000101/01010101001000101000110000000/10100010100100011010011011111/00011101100111000100001101001/01111110000001101000111111001/00000000110110001000100010010/11111110010010101100101011011/10000010101100001010100010100/10111010010001010011111111101/10111010100000101011010011110/10111010100110111010000011001/10000010101010101110100111011/11111110000111010110110101100",
                "11111110001001101111001111111/10000010001100011000101000001/10111010111010111101101011101/10111010010001100101001011101/10111010111111011010001011101/10000010101111110011001000001/11111110101010101010101111111/00000000010110100110100000000/01001010111110101011110110100/01100100000111100001111101101/01111011111001000100011010111/11111001011010001100000111010/00101110100111000010011011110/10010100001111111110111111000/10000110100010101011100111101/00000000000100100000001011011/11111011001100101001100111101/11111101100010000010011010101/00101110101010010100010101110/00100101011111111100110101110/11010110101011000010111111100/00000000100111111001100011100/11111110001010010100101011100/10000010011001011111100011110/10111010100000100010111110011/10111010000111101100010100110/10111010010011101111010110011/10000010100100100000101001010/11111110000000010001110010100",
                "11111110110101110011001111111/10000010101101111001001000001/10111010010100110011101011101/10111010000111010011101011101/10111010001110101011101011101/10000010001110010010101000001/11111110101010101010101111111/00000000001000111000100000000/01000011101111011010010000011/00101101001110101000110100100/11110111110111001010010100110/01100101000100010010011001001/00110010111011011110000111101/11110101101110011111011110100/00001010101100100101101001100/10110100110010010110111101101/10001010111101011000010110011/10011100000011100011111011001/11001111001001110111110110010/10111001000001100010101011101/10100111011010110011111110010/00000000101110110000100010101/11111110100100011010101011101/10000010000111000001100011101/10111010011100111110111110000/10111010000110001101110101010/10111010011101100001011000010/10000010110010010110011111100/11111110010001100000000011010",
                "11111110010101110011001111111/10000010101100011000101000001/10111010011101111010101011101/10111010100111010011101011101/10111010101010001111001011101/10000010000010011110101000001/11111110101010101010101111111/00000000101001011001000000000/01011110100110010011011011010/00101101001110101000110100100/11010011010011101110110000010/01101001001000011110010101000/00110010111011011110000111101/10010100001111111110111111000/01000011100101101100100000101/10110100110010010110111101101/10101110011001111100110010111/10010000001111101111110111000/11001111001001110111110110010/11011000100000000011001010001/11101110010011111010111111011/00000000101110110000100010101/11111110000000111110101011001/10000010101011001101100011100/10111010111100111110111110000/10111010100111101100010100110/10111010010100101000010001011/10000010110010010110011111100/11111110010101000100100111110",
                "11111110100000100110001111111/10000010010011100111001000001/10111010101000101111101011101/10111010111000101100001011101/10111010011111011010001011101/10000010111101100001001000001/11111110101010101010101111111/00000000110110100110100000000/01010111110011000110011101101/11010000110001010111001011011/10000110000110111011100101000/10010100110111100001101010111/01100111101110001011010010111/01101001110000000001000000111/00010110110000111001110101111/01001001001101101001000010010/11111011001100101001100111101/01101101110000010000001000111/10011010011100100010100011000/00100101011111111100110101110/10111011000110101111111110001/00000000110001001111100011010/11111110110101101011101010011/10000010110100110010100010011/10111010001001101011111111010/10111010111000010011101011001/10111010000001111101000100001/10000010101101101001100000011/11111110000000010001110010100",
            ),
            scores = listOf(647, 691, 612, 812, 603, 749, 544, 715),
        ),
        ReferenceCase(
            text = "DPW1:P:CMT0PROTOTYPE0001ABC:5QVC",
            version = 3,
            level = DwQrEccLevel.Q,
            masks = listOf(
                "11111110100011101100101111111/10000010110010000101101000001/10111010110111000110101011101/10111010111000000111101011101/10111010110001111100101011101/10000010011001101010001000001/11111110101010101010101111111/00000000110011000000100000000/01101011001011110001101011111/11000001001111100101110110111/00001110111110111111101101101/01011000100011101010101010011/01100111110101001111010010111/00011000100100000111101001110/10110110010001111010001001010/00100001110011100110010010000/10001010011011111010001100100/01110100110111111011010111111/10100111001101011010000001101/01110000110110101001001011010/10110010101001111100111110101/00000000100011110000100011010/11111110101110001101101011001/10000010001111010001100010110/10111010101100100000111111001/10111010000101110100110011000/10111010111111000100101111001/10000010101011110101001000111/11111110000011001011000110001",
                "11111110010110111001101111111/10000010000111010000101000001/10111010000010010011101011101/10111010101101010010101011101/10111010000100101001101011101/10000010101100111111001000001/11111110101010101010101111111/00000000100110010101100000000/01100010011110100100101101000/10010100011010110000100011101/01011011101011101010111000111/00001101110110111111111111001/00110010100000011010000111101/01001101110001010010111100100/11100011000100101111011100000/01110100100110110011000111010/11011111001110101111011001110/00100001100010101110000010101/11110010011000001111010100111/00100101100011111100011110000/11100111111100101001111111111/00000000110110100101100010000/11111110011011011000101010011/10000010011010000100100011100/10111010011001110101111110011/10111010010000100001100110010/10111010101010010001111010011/10000010111110100000011101101/11111110010110011110010011011",
                "11111110111011010100001111111/10000010010101000010101000001/10111010001111111110001011101/10111010011111000000101011101/10111010101001000100001011101/10000010111110101101001000001/11111110101010101010101111111/00000000010100000111100000000/01111111010011001001000110001/00000100001000100010110001111/00110110000110000111010101010/10011101100100101101101101011/01011111001101110111101010000/11011101100011000000101110110/10001110101001000010110001101/11100100110100100001010101000/10110010100011000010110100011/10110001110000111100010000111/10011111110101100010111001010/10110101110001101110001100010/10001010010001000100111110010/00000000100100110111100010010/11111110110110110101101011110/10000010101000010110100011110/10111010110100011000111111110/10111010100010110011110100000/10111010100111111100010111110/10000010101100110010001111111/11111110011011110011111110110",
                "11111110011011010100001111111/10000010100011110100001000001/10111010110100100101001011101/10111010011111000000101011101/10111010011111110010101011101/10000010000101110110001000001/11111110101010101010101111111/00000000000010110001000000000/01110110001000010010000000110/00000100001000100010110001111/10000010110000110001100011100/01000100111111110110110110000/01011111001101110111101010000/01101001010101110110011000000/01010111110010011001101010110/11100100110100100001010101000/00000110010101110100000010101/01101000101011100111001011100/10011111110101100010111001010/00000001000111011000111010100/01010011001010011111111111001/00000000100100110111100010010/11111110000000000011101011000/10000010110011001101100010101/10111010010100011000111111110/10111010110100000101000010110/10111010111100100111001100101/10000010101100110010001111111/11111110001101000101001000000",
                "11111110001010100101101111111/10000010000100110011001000001/10111010100001110000001011101/10111010010001001110101011101/10111010111000110101101011101/10000010101111011100101000001/11111110101010101010101111111/00000000011010001001100000000/01001010100010111000110110100/01110101111001010011000000001/10111010001000001001011011011/00010001101010100011100011010/00101110111100000110011011110/10101100010010110001011111000/00000010100111001100111111100/01101000111010101111011011001/11000011010010110011000101101/11000000000001001101100001001/00010011111011101100110111011/00111001111111100000000010011/11111011100000110101111111100/00000000110101000110100011100/11111110011000111011101011111/10000010000110011000100011111/10111010100101101001111110000/10111010010011000010000101110/10111010001001110010011001111/10000010100010111100000001110/11111110001010000010001111000",
                "11111110110110111001101111111/10000010100101010010101000001/10111010001111111110001011101/10111010000111111000001011101/10111010001001000100001011101/10000010001110111101001000001/11111110101010101010101111111/00000000000100010111100000000/01000011110011001001010000011/00111100110000011010001001000/00110110000110000111010101010/10001101110100111101111101001/00110010100000011010000111101/11001101110011010000111110100/10001110101001000010110001101/11011100001100011001101101111/10110010100011000010110100011/10100001100000101100000000101/11110010011000001111010100111/10100101100001111110011100000/10001010010001000100111110010/00000000111100001111100010101/11111110110110110101101011110/10000010011000000110100011100/10111010011001110101111110011/10111010010010100011100100010/10111010000111111100010111110/10000010110100001010110111000/11111110011011110011111110110",
                "11111110010110111001101111111/10000010100100110011001000001/10111010000110110111001011101/10111010100111111000001011101/10111010101101100000101011101/10000010000010110001001000001/11111110101010101010101111111/00000000100101110110000000000/01011110111010000000011011010/00111100110000011010001001000/00010010100010100011110001110/10000001111000110001110001000/00110010100000011010000111101/10101100010010110001011111000/11000111100000001011111000100/11011100001100011001101101111/10010110000111100110010000111/10101101101100100000001100100/11110010011000001111010100111/11000100000000011111111101100/11000011011000001101111111011/00000000111100001111100010101/11111110010010010001101011010/10000010110100001010100011101/10111010111001110101111110011/10111010110011000010000101110/10111010001110110101011110111/10000010110100001010110111000/11111110011111010111011010010",
                "11111110100011101100101111111/10000010011011001100101000001/10111010110011100010001011101/10111010111000000111101011101/10111010011000110101101011101/10000010111101001110101000001/11111110101010101010101111111/00000000111010001001100000000/01010111101111010101011101101/11000001001111100101110110111/01000111110111110110100100100/01111100000111001110001110111/01100111110101001111010010111/01010001101101001110100000111/10010010110101011110101101110/00100001110011100110010010000/11000011010010110011000101101/01010000010011011111110011011/10100111001101011010000001101/00111001111111100000000010011/10010110001101011000111110001/00000000100011110000100011010/11111110100111000100101010000/10000010101011110101100010010/10111010001100100000111111001/10111010101100111101111010001/10111010011011100000001011101/10000010101011110101001000111/11111110001010000010001111000",
            ),
            scores = listOf(685, 637, 644, 780, 568, 824, 770, 700),
        ),
        ReferenceCase(
            text = "DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER",
            version = 4,
            level = DwQrEccLevel.Q,
            masks = listOf(
                "111111101001111111001110101111111/100000101001111010101010101000001/101110101011101001111110101011101/101110101011001011111111101011101/101110101000100001101010101011101/100000100110001000011001101000001/111111101010101010101010101111111/000000001110000011110011000000000/011010110001100101100000001011111/110101010100010001010100001101111/001010101010110010110011110001000/100010000100110001110111001110010/001100111110110000101010100000100/110001010000000110111011100001111/011110101110100011011101100100001/000111010100010101001100100101111/011000100101000100011001111000111/011001001110000001000011011111001/100010110010111100001101010001010/111000010010101000011001000110110/101010101111101101000000111011011/011100010111101100110101010001011/100010111101011100100110101100101/011100011100110100010011010110010/101110110001011001000110111110100/000000001000011110100111100011011/111111101101101001101010101011110/100000100011111110001110100011110/101110101100111110011010111111011/101110100011101100011011001011000/101110101110010111111011100101101/100000101001011011001011011000111/111111100111111011010110001100101",
                "111111100100101010011011101111111/100000100100101111111111101000001/101110100110111100101011101011101/101110101110011110101010101011101/101110100101110100111111101011101/100000101011011101001100101000001/111111101010101010101010101111111/000000001011010110100110000000000/011000100100110000110101001101000/100000000001000100000001011000101/011111111111100111100110100100010/110111010001100100100010011011000/011001101011100101111111110101110/100100000101010011101110110100101/001011111011110110001000110001011/010010000001000000011001110000101/001101110000010001001100101101101/001100011011010100010110001010011/110111100111101001011000000100000/101101000111111101001100010011100/111111111010111000010101101110001/001001000010111001100000000100001/110111101000001001110011111001111/001001001001100001000110000011000/111011100100001100010011111111110/000000001101001011110010100010001/111111100000111100111111101010100/100000100110101011011011100010100/101110100001101011001111111110001/101110100110111001001110011110010/101110101011000010101110110000111/100000101100001110011110001101101/111111100010101110000011011001111",
                "111111101111110001000000101111111/100000100000001011011011001000001/101110100101100111110000101011101/101110100010111010001110001011101/101110101110101111100100101011101/100000101111111001101000001000001/111111101010101010101010101111111/000000000111110010000010100000000/011111110111101011101110000110001/000100000101100000100101111100001/000100100100111100111101111111001/010011010101000000000110111111100/000010110000111110100100101110101/000000000001110111001010010000001/010000100000101101010011101010000/110110000101100100111101010100001/010110101011001010010111110110110/101000011111110000110010101110111/101100111100110010000011011111011/001001000011011001101000110111000/100100100001100011001110110101010/101101000110011101000100100000101/101100110011010010101000100010100/101101001101000101100010100111100/100000111111010111001000111110101/000000001001101111010110100010101/111111101011100111100100101011111/100000101010001111111111100010000/101110101010110000010100111111010/101110101010011101101010111010110/101110101000011001110101101011100/100000101000101010111010101001001/111111100001110101011000000010100",
                "111111100111110001000000101111111/100000101101100110110110101000001/101110101011010001000110001011101/101110100010111010001110001011101/101110100011000010001001001011101/100000100001001111011110101000001/111111101010101010101010101111111/000000000010011111101111000000000/011101100001011101011000100000110/000100000101100000100101111100001/101001101001010001010000010010100/100101000011110110110000001001010/000010110000111110100100101110101/101101001100011010100111111101100/100110110110011011100101011100110/110110000101100100111101010100001/111011100110100111111010011011011/011110001001000110000100011000001/101100111100110010000011011111011/100100001110110100000101011010101/010010110111010101111000000011100/101101000110011101000100100000101/000001111110111111000101001111001/011011011011110011010100010001010/100000111111010111001000111110101/000000001100000010111011100011000/111111100101010001010010101011001/100000101010001111111111100010000/101110100111011101111001111110111/101110101100101011011100001100000/101110101000011001110101101011100/100000101101000111010111000100100/111111100111000011101110110100010",
                "111111100011101101011100101111111/100000100100010111000111001000001/101110101110000100010011001011101/101110100001011001101101101011101/101110101010110011111000101011101/100000101011100101110100001000001/111111101010101010101010101111111/000000000100010001100001000000000/010010101011110111110010010110100/011000011001111100111001100000010/100111100111011111011110011100101/110000010110100011100101011100000/011110101100100010111000110010110/011100011101101011010110001100010/110011100011001110110000001001100/010101000110000111011110110111101/001010110111010110001011101010101/110100000011101100101110110010100/001111111111010001100000111100111/101010000000111010001011010100100/111000111101111111010010101001001/110001011010000001011000111100110/001111110000110001001011000001000/001110001110100110000001000100000/111100100011001011010100111110110/000000001101110011001010100010110/111111100000000100000111101010011/100000100001101100011100100011100/101110101110101100001000111111001/101110100110000001110110100110101/101110100011111010010110001000000/100000101011001001011001001010101/111111100101101001000100011110111",
                "111111101100101010011011101111111/100000101100001111011111001000001/101110100101100111110000101011101/101110100100110100000000001011101/101110100110101111100100101011101/100000100011111101101100001000001/111111101010101010101010101111111/000000000011110110000110100000000/010000111111101011101110010000011/001010001011101110101011110010000/000100100100111100111101111111001/010111010001000100000010111011100/011001101011100101111111110101110/000100000101110011001110010100001/010000100000101101010011101010000/111000001011101010110011011010000/010110101011001010010111110110110/101100011011110100110110101010111/110111100111101001011000000100000/001101000111011101101100110011000/100100100001100011001110110101010/100011001000010011001010101110100/101100110011010010101000100010100/101001001001000001100110100011100/111011100100001100010011111111110/000000001101101011010010100010101/111111101011100111100100101011111/100000100100000001110001100010001/101110100010110000010100111111010/101110100110011001101110111110110/101110100011000010101110110000111/100000101100101110111110101101001/111111100001110101011000000010100",
                "111111100100101010011011101111111/100000101100010111000111001000001/101110100111110101100010101011101/101110101100110100000000001011101/101110101111100110101101101011101/100000100000111110101111001000001/111111101010101010101010101111111/000000001011101110011110100000000/010111101101111001111100011011010/001010001011101110101011110010000/001101101101110101110100110110000/010100010010000111000001111000100/011001101011100101111111110101110/011100011101101011010110001100010/000010110010111111000001111000010/111000001011101010110011011010000/011111100010000011011110111111111/101111011000110111110101101001111/110111100111101001011000000100000/010101011111000101110100101011011/110110110011110001011100100111000/100011001000010011001010101110100/100101111010011011100001101011101/101010001010000010100101100000100/111011100100001100010011111111110/000000001101110011001010100010110/111111100001110101110110101011101/100000101100000001110001100010001/101110101011111001011101111110011/101110101101011010101101111101110/101110100011000010101110110000111/100000101100110110100110110101010/111111100011100111001010010000110",
                "111111101001111111001110101111111/100000100011101000111000101000001/101110101010100000110111101011101/101110101011001011111111101011101/101110100010110011111000101011101/100000101111000001010000101000001/111111101010101010101010101111111/000000001100010001100001000000000/010101111000101100101001011101101/110101010100010001010100001101111/011000111000100000100001100011010/101011001101111000111110000111011/001100111110110000101010100000100/100011000010010100101001110011101/010111100111101010010100101101000/000111010100010101001100100101111/001010110111010110001011101010101/010000000111001000001010010110000/100010110010111100001101010001010/101010000000111010001011010100100/100011100110100100001001110010010/011100010111101100110101010001011/110000101111001110110100111110111/010101010101111101011010011111011/101110110001011001000110111110100/000000001010001100110101100011001/111111101100100000100011101010111/100000101011111110001110100011110/101110100110101100001000111111001/101110101010100101010010000010001/101110100110010111111011100101101/100000101011001001011001001010101/111111100110110010011111000101100",
            ),
            scores = listOf(647, 799, 860, 934, 721, 919, 820, 646),
        ),
        ReferenceCase(
            text = "DPW1:P:ABCDEFGH:0000",
            version = 1,
            level = DwQrEccLevel.M,
            masks = listOf(
                "111111100001101111111/100000101100001000001/101110100101001011101/101110100010101011101/101110101110101011101/100000100011001000001/111111101010101111111/000000000101000000000/101010100010100010010/101111000111011101111/100100111000100111001/001111001111001010011/111001111010100000111/000000001011011011011/111111100110100101001/100000100011001000111/101110101110101011000/101110100001001010010/101110101000011011101/100000100011011110011/111111101111111101001",
                "111111101100101111111/100000100001001000001/101110101000001011101/101110100111101011101/101110100011101011101/100000101110001000001/111111101010101111111/000000000000000000000/101000110111100100101/111010010010001000101/110001101101110010011/011010011010011111001/101100101111110101101/000000001110001110001/111111101011110000011/100000100110011101101/101110100011111110010/101110100100011111000/101110101101001110111/100000100110001011001/111111101010101000011",
                "111111100111101111111/100000100101101000001/101110101011001011101/101110101011001011101/101110101000101011101/100000101010101000001/111111101010101111111/000000001100100000000/101111100100101111100/011110010110101100001/101010110110101001000/111110011110111011101/110111110100101110110/000000001010101010101/111111100000101011000/100000101010111001001/101110101000100101001/101110101000111011100/101110101110010101100/100000100010101111101/111111101001110011000",
                "111111101111101111111/100000101000001000001/101110100101101011101/101110101011001011101/101110100101001011101/100000100100001000001/111111101010101111111/000000001001000000000/101101110010001001011/011110010110101100001/000111111011000100101/001000001000001101011/110111110100101110110/000000001111000111000/111111101110011101110/100000101010111001001/101110100101001000100/101110101110001101010/101110101110010101100/100000100111000010000/111111101111000101110",
                "111111101011101111111/100000100001101000001/101110100000101011101/101110101000101011101/101110101100101011101/100000101110101000001/111111101010101111111/000000001111000000000/100010111000111111001/000010001010110000010/001001110101001010100/011101011101011000001/101011101000110010101/000000001110110110110/111111101011001000100/100000100001011010101/101110101100111001010/101110100100100111111/101110100101110110000/100000100001001100001/111111101101101111011",
                "111111100100101111111/100000101001101000001/101110101011001011101/101110101101001011101/101110100000101011101/100000100110101000001/111111101010101111111/000000001000100000000/100000101100111001110/010000011000100010000/101010110110101001000/111010011010111111101/101100101111110101101/000000001110101110101/111111100000101011000/100000100100110111000/101110100000100101001/101110100100111111100/101110100101001110111/100000100110101011101/111111101001110011000",
                "111111101100101111111/100000101001101000001/101110101001001011101/101110100101001011101/101110101001101011101/100000100101101000001/111111101010101111111/000000000000100000000/100111111110110010111/010000011000100010000/100011111111100000001/111001011001111100101/101100101111110101101/000000001110110110110/111111101010111001010/100000101100110111000/101110101001101100000/101110101111111100100/101110100101001110111/100000100110110011110/111111101011100001010",
                "111111100001101111111/100000100110001000001/101110100100001011101/101110100010101011101/101110100100101011101/100000101010001000001/111111101010101111111/000000000111000000000/100101101011110100000/101111000111011101111/110110101010110101011/000110000110000011010/111001111010100000111/000000001001001001001/111111100111101100000/100000101011001000111/101110100100111001010/101110101000000011011/101110100000011011101/100000100001001100001/111111101110110100000",
            ),
            scores = listOf(286, 414, 319, 334, 401, 464, 428, 442),
        ),
        ReferenceCase(
            text = "DPW1:P:ABCDEFGH:0000",
            version = 2,
            level = DwQrEccLevel.H,
            masks = listOf(
                "1111111010111110101111111/1000001001011111001000001/1011101000111101101011101/1011101010001111001011101/1011101001011011001011101/1000001000110010101000001/1111111010101010101111111/0000000001010010000000000/0010111010001000010001001/1110010111100011110011010/0111101100101001110001011/0000110100111100000110101/0010011001010001100001000/0101110010001011000010011/1001011001101100111001101/0110110011010111010110011/1001001101101110111111011/0000000011101111100010111/1111111000110110101010001/1000001010111101100011011/1011101011101000111111000/1011101000101001011101010/1011101011110010101111101/1000001000001001010010111/1111111000111010101001001",
                "1111111001101011101111111/1000001010001010001000001/1011101011101000101011101/1011101011011010001011101/1011101010001110001011101/1000001011100111101000001/1111111010101010101111111/0000000000000111000000000/0010011111011101010111110/1011000010110110100110000/0010111001111100100100001/0101100001101001010011111/0111001100000100110100010/0000100111011110010111001/1100001100111001101100111/0011100110000010000011001/1100011000111011111110001/0000000010111010100011101/1111111011100011101011011/1000001011101000100010001/1011101000111101111110010/1011101001111100001000000/1011101010100111111010111/1000001001011100000111101/1111111001101111111100011",
                "1111111011011101001111111/1000001011000011001000001/1011101011011110001011101/1011101000010011001011101/1011101000111000101011101/1000001010101110101000001/1111111010101010101111111/0000000011001110000000000/0011101011101011111100111/0010000011111111101111001/0100001111001010010010111/1100100000100000011010110/0001111010110010000010100/1001100110010111011110000/1010111010001111011010001/1010100111001011001010000/1010101110001101111110111/0000000011110011100010100/1111111001010101101011101/1000001000100001100011000/1011101010001011111110100/1011101010110101000001001/1011101010010001001100001/1000001000010101001110100/1111111001011001001010101",
                "1111111001011101001111111/1000001000011000001000001/1011101000110011101011101/1011101000010011001011101/1011101011100011101011101/1000001001000011001000001/1111111010101010101111111/0000000010010101000000000/0011001110000110011010000/0010000011111111101111001/1111011100010001001001100/0001000101001101110111011/0001111010110010000010100/0010110101001100000101011/0111011111100010110111100/1010100111001011001010000/0001111101010110111111100/0000000010011110100011001/1111111011010101101011101/1000001001111010100010011/1011101001100110111111001/1011101010110101000001001/1011101011001010010111010/1000001001111000100011001/1111111001011001001010101",
                "1111111000011010001111111/1000001010000100001000001/1011101001100110101011101/1011101000101011101011101/1011101001111111101011101/1000001011101001101000001/1111111010101010101111111/0000000011110110100000000/0000111100101100101100010/0101000100111000101000001/1100111111110010101010000/0100010000011000100010001/0110111101110101000101100/1110100001010000011001000/0010001010110111100010110/0010010111110011110010111/1101101001001010111111111/0000000010110100100011100/1111111011101101101011010/1000001010011001100011111/1011101011001100111111100/1011101001110010000110001/1011101000101001110100110/1000001000101101110110011/1111111000011110001101101",
                "1111111011101011101111111/1000001000000010001000001/1011101011011110001011101/1011101001110000101011101/1011101010111000101011101/1000001001101111101000001/1111111010101010101111111/0000000010001111000000000/0000011001101011101010101/0001100000011100001100101/0100001111001010010010111/1101100001100001011011110/0111001100000100110100010/1000100111010110011111000/1010111010001111011010001/1001000100101000101001100/1010101110001101111110111/0000000010110010100011100/1111111001100011101011011/1000001011100000100010000/1011101000001011111110100/1011101001010110100010101/1011101000010001001100001/1000001001010100001111100/1111111001101111111100011",
                "1111111001101011101111111/1000001000000100001000001/1011101011111010101011101/1011101011110000101011101/1011101000101010101011101/1000001001011111001000001/1111111010101010101111111/0000000000001001000000000/0001101101001111000001100/0001100000011100001100101/0110011101011000000000101/1101010001010001101011000/0111001100000100110100010/1110100001010000011001000/1110011110101011111110101/1001000100101000101001100/1000111100011111111110101/0000000010000010100011010/1111111011100011101011011/1000001001100110100010000/1011101010101111111110000/1011101011010110100010101/1011101000000011011110011/1000001001100100111111010/1111111001101111111100011",
                "1111111010111110101111111/1000001011111011101000001/1011101000101111101011101/1011101010001111001011101/1011101011111111101011101/1000001010100000101000001/1111111010101010101111111/0000000001110110100000000/0001001000011010000111011/1110010111100011110011010/0011001000001101010101111/0010100110101110010100111/0010011001010001100001000/0001010110101111100110111/1011001011111110101011111/0110110011010111010110011/1101101001001010111111111/0000000011111101100010101/1111111000110110101010001/1000001000011001100011111/1011101001111010111111010/1011101010101001011101010/1011101001010110001011001/1000001000011011000000101/1111111000111010101001001",
            ),
            scores = listOf(410, 480, 517, 455, 635, 506, 648, 478),
        ),
        ReferenceCase(
            text = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            version = 6,
            level = DwQrEccLevel.L,
            masks = listOf(
                "11111110001001010111110101110010001111111/10000010011010111010101111101011001000001/10111010100011100111111101000101101011101/10111010000011101110100001111001101011101/10111010001011101110100101011000001011101/10000010001100011011111010100010101000001/11111110101010101010101010101010101111111/00000000111110110111001110001011100000000/11101111111011100100010011110001111000100/11100000101101011110100001101001111001011/01000111001000110010000101000110110111000/11111100111011100111101000101001011000101/11011110101010111011100101111101010111000/11110001101110111111101010101110011000110/01011111110111011001110000010001110101011/01101101010011101010100010111100110000001/11000010100101011101011100001001101101000/00010100111110101001111010101111110111001/10111011001001011101010001100101100111111/10011100001010001000001110011110101101100/11100110111000010001011111010001110101010/00001001011101010001001001101110100010011/10101011100000000100000101000011001001101/01110100011100110001001000101101110110111/00101010001001000110100101110010101010011/11100001010011011110101111110010110010100/00110111110110100011101101011100010100010/11011100101001101110110001111001000000001/10101010011110101011101100011100011100100/01111000011110111011110010100000101110111/10001111000111011001110001110110001100001/01111100111010001010101110000010110001101/10001010101101110001011111011101111111010/00000000110000101011101010100011100011011/11111110111101011100010111000110101011010/10000010100010000000100010011010100010111/10111010100000010000000101000001111111010/10111010010100010101000111001011010110110/10111010101000000100011101000001011111111/10000010110100110011000001111111111110111/11111110110000000110010100010110010001101",
                "11111110111100000010100000100111001111111/10000010101111101111111010111110001000001/10111010010110110010101000010000101011101/10111010010110111011110100101100101011101/10111010111110111011110000001101001011101/10000010111001001110101111110111101000001/11111110101010101010101010101010101111111/00000000101011100010011011011110100000000/11100110101110110001000110100100111110011/10110101111000001011110100111100101100001/00010010011101100111010000010011100010010/10101001101110110010111101111100001101111/10001011111111101110110000101000000010010/10100100111011101010111111111011001101100/00001010100010001100100101000100100000001/00111000000110111111110111101001100101011/10010111110000001000001001011100111000010/01000001101011111100101111111010100010011/11101110011100001000000100110000110010101/11001001011111011101011011001011111000110/10110011101101000100001010000100100000000/01011100001000000100011100111011110111001/11111110110101010001010000010110011100111/00100001001001100100011101111000100011101/01111111011100010011110000100111111111001/10110100000110001011111010100111100111110/01100010100011110110111000001001000001000/10001001111100111011100100101100010101011/11111111001011111110111001001001001001110/00101101001011101110100111110101111011101/11011010010010001100100100100011011001011/00101001101111011111111011010111100100111/11011111111000100100001010001000111110000/00000000100101111110111111110110100010001/11111110001000001001000010010011101010000/10000010110111010101110111001111100011101/10111010010101000101010000010100111110000/10111010000001000000010010011110000011100/10111010111101010001001000010100001010101/10000010100001100110010100101010101011101/11111110100101010011000001000011000100111",
                "11111110010001101111001101001010101111111/10000010111101111101101000101100001000001/10111010011011011111000101111101001011101/10111010100100101001100110111110101011101/10111010010011010110011101100000101011101/10000010101011011100111101100101101000001/11111110101010101010101010101010101111111/00000000011001110000001001001100100000000/11111011100011011100101011001001010101010/00100101101010011001100110101110111110011/01111111110000001010111101111110001111111/00111001111100100000101111101110011111101/11100110010010000011011101000101101111111/00110100101001111000101101101001011111110/01100111001111100001001000101001001101100/10101000010100101101100101111011110111001/11111010011101100101100100110001010101111/11010001111001101110111101101000110000001/10000011110001100101101001011101011111000/01011001001101001111001001011001101010100/11011110000000101001100111101001001101101/11001100011010010110001110101001100101011/10010011011000111100111101111011110001010/10110001011011110110001111101010110001111/00010010110001111110011101001010010010100/00100100010100011001101000110101110101100/00001111001110011011010101100100101100101/00011001101110101001110110111110000111001/10010010100110010011010100100100100100011/10111101011001111100110101100111101001111/10110111111111100001001001001110110100110/10111001111101001101101001000101110110101/10110010010101001001100111100101111111101/00000000110111101100101101100100100010011/11111110100101100100101111111110101011101/10000010000101000111100101011101100011111/10111010111000101000111101111001111111101/10111010110011010010000000001100010001110/10111010110000111100100101111001100111000/10000010110011110100000110111000111001111/11111110101000111110101100101110101001010",
                "11111110110001101111001101001010101111111/10000010001011001011011110011010101000001/10111010100000000100011110100110001011101/10111010100100101001100110111110101011101/10111010100101100000101011010110001011101/10000010010000000111100110111110101000001/11111110101010101010101010101010101111111/00000000001111000110111111111010000000000/11110010111000000111110000010010010011101/00100101101010011001100110101110111110011/11001011000110111100001011001000111001001/11100000100111111011110100110101000100110/11100110010010000011011101000101101111111/10000000011111001110011011011111101001000/10111110010100111010010011110010010110111/10101000010100101101100101111011110111001/01001110101011010011010010000111100011001/00001000100010110101100110110011101011010/10000011110001100101101001011101011111000/11101101111011111001111111101111011100010/00000111011011110010111100110010010110110/11001100011010010110001110101001100101011/00100111101110001010001011001101000111100/01101000000000101101010100110001101010100/00010010110001111110011101001010010010100/10010000100010101111011110000011000011010/11010110010101000000001110111111110111110/00011001101110101001110110111110000111001/00100110010000100101100010010010010010101/01100100000010100111101110111100110010100/10110111111111100001001001001110110100110/00001101001011111011011111110011000000011/01101011001110010010111100111110111110110/00000000110111101100101101100100100010011/11111110010011010010011001001000101011011/10000010011110011100111110000110100010100/10111010011000101000111101111001111111101/10111010100101100100110110111010100111000/10111010101011100111111110100010111100011/10000010110011110100000110111000111001111/11111110111110001000011010011000011111100",
                "11111110100000011110111100111011001111111/10000010101100001100011001011101101000001/10111010110101010001001011110011001011101/10111010101010100111101000110000101011101/10111010000010100111101100010001001011101/10000010111010101101001100010100001000001/11111110101010101010101010101010101111111/00000000010111111110000111000010100000000/11001110010010101101011010111000100101111/01010100011011101000010111011111001111101/11110011111110000100110011110000000001110/10110101110010101110100001100000010001100/10010111100011110010101100110100011110001/01000101011000001001011100011000101110000/11101011000001101111000110100111000011101/00100100011010100011101011110101111001000/10001011101100010100010101000000100100001/10100000001000011111001100011001000001111/00001111111111101011100111010011010001001/11010101000011000001000111010111100100101/10101111110001011000010110011000111100011/10111101101011100111111111011000010100101/00011111010110110010110011110101111111011/00111101010101111000000001100100111111110/01100011000000001111101100111011100011010/01010101100101101000011001000100000100010/10000011000000010101011011101010100010100/10010101100000100111111000110000001001000/11100011010111100010100101010101010101101/11001100101000001101000100010110011000001/00111011110001101111000111000000111010111/00110101110011000011100111001011111000100/11000011100100111000010110010100111110011/00000000100110011101011100010101100011101/11111110001011101010100001110000101011100/10000010101011001001101011010011100011110/10111010101001011001001100001000111110011/10111010000010100011110001111101100000000/10111010011110110010101011110111101001001/10000010111101111010001000110110110111110/11111110111001001111011101011111011000100",
                "11111110011100000010100000100111001111111/10000010001101101101111000111100001000001/10111010011011011111000101111101001011101/10111010111100010001011110000110001011101/10111010110011010110011101100000101011101/10000010011011001100101101110101101000001/11111110101010101010101010101010101111111/00000000001001100000011001011100100000000/11000111000011011100101011001001000011000/00011101010010100001011110010110000110100/01111111110000001010111101111110001111111/00101001101100110000111111111110001111111/10001011111111101110110000101000000010010/00100100111001101000111101111001001111100/01100111001111100001001000101001001101100/10010000101100010101011101000011001111110/11111010011101100101100100110001010101111/11000001101001111110101101111000100000011/11101110011100001000000100110000110010101/01001001011101011111011001001001111010110/11011110000000101001100111101001001101101/11110100100010101110110110010001011101100/10010011011000111100111101111011110001010/10100001001011100110011111111010100001101/01111111011100010011110000100111111111001/00110100000100001001111000100101100101110/00001111001110011011010101100100101100101/00100001010110010001001110000110111111110/10010010100110010011010100100100100100011/10101101001001101100100101110111111001101/11011010010010001100100100100011011001011/10101001101101011101111001010101100110111/10110010010101001001100111100101111111101/00000000101111010100010101011100100010100/11111110100101100100101111111110101011101/10000010110101010111110101001101100011101/10111010010101000101010000010100111110000/10111010000011000010010000011100000001100/10111010010000111100100101111001100111000/10000010101011001100111110000000000001000/11111110101000111110101100101110101001010",
                "11111110111100000010100000100111001111111/10000010001100001100011001011101101000001/10111010010010010110001100110100001011101/10111010011100010001011110000110001011101/10111010010111110010111001000100001011101/10000010010111000000100001111001101000001/11111110101010101010101010101010101111111/00000000101000000001111000111101000000000/11011010001010010101100010000000001000001/00011101010010100001011110010110000110100/01011011010100101110011001011010101011011/00100101100000111100110011110010000011110/10001011111111101110110000101000000010010/01000101011000001001011100011000101110000/00101110000110101000000001100000000100101/10010000101100010101011101000011001111110/11011110111001000001000000010101110001011/11001101100101110010100001110100101100010/11101110011100001000000100110000110010101/00101000111100111110111000101000011011010/10010111001001100000101110100000000100100/11110100100010101110110110010001011101100/10110111111100011000011001011111010101110/10101101000111101010010011110110101101100/01111111011100010011110000100111111111001/01010101100101101000011001000100000100010/01000110000111010010011100101101100101100/00100001010110010001001110000110111111110/10110110000010110111110000000000000000111/10100001000101100000101001111011110101100/11011010010010001100100100100011011001011/11001000001100111100011000110100000111011/11111011011100000000101110101100111110100/00000000101111010100010101011100100010100/11111110000001000000001011011010101011001/10000010011001011011111001000001100011100/10111010110101000101010000010100111110000/10111010100010100011110001111101100000000/10111010011001110101101100110000101110001/10000010101011001100111110000000000001000/11111110101100011010001000001010001101110",
                "11111110001001010111110101110010001111111/10000010110011110011100110100010001000001/10111010100111000011011001100001001011101/10111010000011101110100001111001101011101/10111010100010100111101100010001001011101/10000010101000111111011110000110001000001/11111110101010101010101010101010101111111/00000000110111111110000111000010100000000/11010011011111000000110111010101001110110/11100000101101011110100001101001111001011/00001110000001111011001100001111111110001/11011000011111000011001100001101111100001/11011110101010111011100101111101010111000/10111000100111110110100011100111010001111/01111011010011111101010100110101010001111/01101101010011101010100010111100110000001/10001011101100010100010101000000100100001/00110000011010001101011110001011010011101/10111011001001011101010001100101100111111/11010101000011000001000111010111100100101/11000010011100110101111011110101010001110/00001001011101010001001001101110100010011/11100010101001001101001100001010000000100/01010000111000010101101100001001010010011/00101010001001000110100101110010101010011/10101000011010010111100110111011111011101/00010011010010000111001001111000110000110/11011100101001101110110001111001000000001/11100011010111100010100101010101010101101/01011100111010011111010110000100001010011/10001111000111011001110001110110001100001/00110101110011000011100111001011111000100/10101110001001010101111011111001111111110/00000000110000101011101010100011100011011/11111110110100010101011110001111101010011/10000010000110100100000110111110100010011/10111010000000010000000101000001111111010/10111010111101011100001110000010011111111/10111010001100100000111001100101111011011/10000010110100110011000001111111111110111/11111110111001001111011101011111011000100",
            ),
            scores = listOf(1274, 1097, 1118, 1054, 1250, 1179, 1322, 1279),
        ),
        ReferenceCase(
            text = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            version = 5,
            level = DwQrEccLevel.Q,
            masks = listOf(
                "1111111011111000000000100101001111111/1000001010101000010011000000101000001/1011101010001100101100110101101011101/1011101010100100010001001000001011101/1011101010101010110111100011001011101/1000001001001011100110101011001000001/1111111010101010101010101010101111111/0000000011001001000101111010000000000/0110101101001000100011011111001011111/0110100110101001000001101111101001100/0111001011010000111111000101001101010/1000000011110111001110111011100010100/1101011100101111011011111001000111110/0101110110001010011101110000101000011/1010101011101111000100101000011100011/1010110110101010111111111111101001000/1010111100010011100000110111010010001/1011000000100010101111110011010000111/0010001010110001000000100100110000001/0010100011101001100111010111011100101/1011011001011000011000100100010100111/0001110110110000010101100010111010011/0000011100100010110110011011011101010/1001010100101001101000101100000101111/1011011111110111010101010010010001010/0110100000101110100000010100011110101/1000011001111000101001111011110101100/0111110100101011110100011010111110011/1001011111011100101001011111111111010/0000000011110001001010101110100011110/1111111011110110010001000000101011010/1000001000001100000000011110100010010/1011101011010010100000001111111110110/1011101000100011001101011100100011111/1011101011011100100001011000001100101/1000001010111010101101101010000110011/1111111001011111111000011101101000101",
                "1111111000101101010101110000001111111/1000001001111101000110010101101000001/1011101001011001111001100000101011101/1011101011110001000100011101001011101/1011101001111111100010110110001011101/1000001010011110110011111110001000001/1111111010101010101010101010101111111/0000000010011100010000101111000000000/0110001000011101110110001010001101000/0011110011111100010100111010111100110/0010011110000101101010010000011000000/1101010110100010011011101110110111110/1000001001111010001110101100010010100/0000100011011111001000100101111101001/1111111110111010010001111101001001001/1111100011111111101010101010111100010/1111101001000110110101100010000111011/1110010101110111111010100110000101101/0111011111100100010101110001100101011/0111110110111100110010000010001001111/1110001100001101001101110001000001101/0100100011100101000000110111101111001/0101001001110111100011001110001000000/1100000001111100111101111001010000101/1110001010100010000000000111000100000/0011110101111011110101000001001011111/1101001100101101111100101110100000110/0010100001111110100001001111101011001/1100001010001001111100001010111110000/0000000010100100011111111011100010100/1111111000100011000100010101101010000/1000001001011001010101001011100011000/1011101000000111110101011010111111100/1011101001110110011000001001110110101/1011101010001001110100001101011001111/1000001011101111111000111111010011001/1111111000001010101101001000111101111",
                "1111111010011011100011000110101111111/1000001000110100001111011100101000001/1011101001101111001111010110001011101/1011101000111000001101010100001011101/1011101011001001010100000000101011101/1000001011010111111010110111001000001/1111111010101010101010101010101111111/0000000001010101011001100110000000000/0111111100101011000000111100100110001/1010110010110101011101110011110101111/0100101000110011011100100110101110110/0100010111101011010010100111111110111/1110111111001100111000011010100100010/1001100010010110000001101100110100000/1001001000001100100111001011111111111/0110100010110110100011100011110101011/1001011111110000000011010100110001101/0111010100111110110011101111001100100/0001101001010010100011000111010011101/1110110111110101111011001011000000110/1000111010111011111011000111110111011/1101100010101100001001111110100110000/0011111111000001010101111000111110110/0101000000110101110100110000011001100/1000111100010100110110110001110010110/1010110100110010111100001000000010110/1011111010011011001010011000010110000/1011100000110111101000000110100010000/1010111100111111001010111100111110110/0000000011101101010110110010100011101/1111111010010101110010100011101010110/1000001010010000011100000010100010001/1011101010110001000011101100111111010/1011101010111111010001000000111111100/1011101010111111000010111011101111001/1000001010100110110001110110011010000/1111111000111100011011111110001011001",
                "1111111000011011100011000110101111111/1000001011101111010100000111101000001/1011101010000010100010111011101011101/1011101000111000001101010100001011101/1011101000010010001111011011101011101/1000001000111010010111011010101000001/1111111010101010101010101010101111111/0000000000001110000010111101000000000/0111011001000110101101010001000000110/1010110010110101011101110011110101111/1111111011101000000111111101110101101/1001110010000110111111001010010011010/1110111111001100111000011010100100010/0010110001001101011010110111101111011/0100101101100001001010100110010010010/0110100010110110100011100011110101011/0010001100101011011000001111101010110/1010110001010011011110000010100001001/0001101001010010100011000111010011101/0101100100101110100000010000011011101/0101011111010110010110101010011010110/1101100010101100001001111110100110000/1000101100011010001110100011100101101/1000100101011000011001011101110100001/1000111100010100110110110001110010110/0001100111101001100111010011011001101/0110011111110110100111110101111011101/1011100000110111101000000110100010000/0001101111100100010001100111111111101/0000000010000000111011011111100010000/1111111000010101110010100011101010110/1000001011001011000111011001100011010/1011101001011100101110000001111110111/1011101010111111010001000000111111100/1011101011100100011001100000110100010/1000001011001011011100011011110111101/1111111000111100011011111110001011001",
                "1111111001011100100100000001101111111/1000001001110011001000011011101000001/1011101011010111110111101110101011101/1011101000000000110101101100101011101/1011101010001110010011000111101011101/1000001010010000111101110000001000001/1111111010101010101010101010101111111/0000000001101101100001011110100000000/0100101011101100000111111011110110100/1101110101110010011010110100110010111/1100011000001011100100011110010110001/1100100111010011101010011111000110000/1001111000001011111111011101100011010/1110100101010001000110101011110011000/0001111000110100011111110011000111000/1110010010001110011011011011001101100/1110011000110111000100010011110110101/0000010011111001110100101000001011100/1001011001101010011011111111101011010/0110000111001101000011110011111000001/1111111101111100111100000000110000011/1010100101101011001110111001100001000/1011001111111001101101000000000110001/1101110000001101001100001000100001011/1111111011010011110001110110110101110/1101110011110101111011001111000101110/0011001010100011110010100000101110111/0011010000001111010000111110011010111/1101111011111000001101111011111111110/0000000010101010010001110101100010101/1111111000101101001010011011101010001/1000001000101000100100111010100010110/1011101011110110000100101011111110010/1011101001111000010110000111111000100/1011101000000111111010000011010111110/1000001010011110001001001110100010111/1111111001111011011100111001001100001",
                "1111111010101101010101110000001111111/1000001011110101001110011101101000001/1011101001101111001111010110001011101/1011101001011011101110110111101011101/1011101001001001010100000000101011101/1000001000010110111011110110001000001/1111111010101010101010101010101111111/0000000000010100011000100111000000000/0100001110101011000000111100110000011/1001010001010110111110010000010110011/0100101000110011011100100110101110110/0101010110101010010011100110111111111/1000001001111010001110101100010010100/1000100011010111000000101101110101000/1001001000001100100111001011111111111/0101000001010101000000000000010110111/1001011111110000000011010100110001101/0110010101111111110010101110001101100/0111011111100100010101110001100101011/1111110110110100111010001010000001110/1000111010111011111011000111110111011/1110000001001111101010011101000101100/0011111111000001010101111000111110110/0100000001110100110101110001011000100/1110001010100010000000000111000100000/1011110101110011111101001001000011110/1011111010011011001010011000010110000/1000000011010100001011100101000001100/1010111100111111001010111100111110110/0000000010101100010111110011100010101/1111111010100011000100010101101010000/1000001001010001011101000011100011001/1011101000110001000011101100111111010/1011101001011100110010100011011100000/1011101000111111000010111011101111001/1000001011100111110000110111011011000/1111111000001010101101001000111101111",
                "1111111000101101010101110000001111111/1000001011110011001000011011101000001/1011101001001011101011110010101011101/1011101011011011101110110111101011101/1011101011011011000110010010101011101/1000001000100110001011000110101000001/1111111010101010101010101010101111111/0000000010010010011110100001000000000/0101111010001111100100011000011011010/1001010001010110111110010000010110011/0110111010100001001110110100111100100/0101100110011010100011010110001111001/1000001001111010001110101100010010100/1110100101010001000110101011110011000/1101101100101000000011101111011011011/0101000001010101000000000000010110111/1011001101100010010001000110100011111/0110100101001111000010011110111101010/0111011111100100010101110001100101011/1001110000110010111100001100000111110/1100011110011111011111100011010011111/1110000001001111101010011101000101100/0001101101010011000111101010101100100/0100110001000100000101000001101000010/1110001010100010000000000111000100000/1101110011110101111011001111000101110/1111011110111111101110111100110010100/1000000011010100001011100101000001100/1000101110101101011000101110111110100/0000000010011100100111000011100010011/1111111000100011000100010101101010000/1000001011010111011011000101100011001/1011101010010101100111001000111111110/1011101011011100110010100011011100000/1011101000101101010000101001111101011/1000001011010111000000000111101011110/1111111000001010101101001000111101111",
                "1111111011111000000000100101001111111/1000001000001100110111100100001000001/1011101010011110111110100111101011101/1011101010100100010001001000001011101/1011101000001110010011000111101011101/1000001011011001110100111001001000001/1111111010101010101010101010101111111/0000000011101101100001011110100000000/0101011111011010110001001101011101101/0110100110101001000001101111101001100/0011101111110100011011100001101001110/1010010001100101011100101001110000110/1101011100101111011011111001000111110/0001010010101110111001010100001100111/1000111001111101010110111010001110001/1010110110101010111111111111101001000/1110011000110111000100010011110110101/1001010010110000111101100001000010101/0010001010110001000000100100110000001/0110000111001101000011110011111000001/1001001011001010001010110110000110101/0001110110110000010101100010111010011/0100111000000110010010111111111001110/1011000110111011111010111110010111101/1011011111110111010101010010010001010/0010000100001010000100110000111010001/1010001011101010111011101001100111110/0111110100101011110100011010111110011/1101111011111000001101111011111111110/0000000011100011011000111100100011100/1111111011110110010001000000101011010/1000001010101000100100111010100010110/1011101001000000110010011101111110100/1011101010100011001101011100100011111/1011101001111000000101111100101000001/1000001010101000111111111000010100001/1111111001011111111000011101101000101",
            ),
            scores = listOf(1023, 1033, 986, 1179, 1212, 959, 786, 1041),
        ),
        ReferenceCase(
            text = "DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER:XXXX",
            version = 5,
            level = DwQrEccLevel.H,
            masks = listOf(
                "1111111010111000001111100101101111111/1000001001101110010110001001001000001/1011101001000111100111000001001011101/1011101011100101100100100110101011101/1011101000100100010100110011101011101/1000001001100000010110011101001000001/1111111010101010101010101010101111111/0000000000110100101000100110000000000/0010111011000010110100110011110001001/0011010001110101010001111011110100111/0101011101011001100011101011101011101/1001110011010100100000100110100110101/1010111000011110010011110101100001100/0111100001010001101010010101101110010/1001111010100000000101110001010010000/0001000100110000101111101101011111000/0100011011110000110011010110011010001/0110110010011110111011000100000011011/0000001101111101111110000111000100100/1001010111010011111001111111011101111/0010111010101111010110111101100101000/0011100100101011010111100000010010001/0101011000100101110001111101110100110/1101110110100000001010110000011100110/1011111100010010011110011001010010000/0000000000100001111010011101010101100/1001001101100010100111111010010111000/0111100110011111010010101001000010101/1000001001001000100110011100111111101/0000000011010110110011111101100011110/1111111001111000011111000010101011011/1000001011100010101110011010100010010/1011101010011111000001110010111110111/1011101000101111100110110011001011000/1011101010101101111110000100110101101/1000001001001001100100010010110101111/1111111001101101101100100010010110101",
                "1111111001101101011010110000101111111/1000001010111011000011011100001000001/1011101010010010110010010100001011101/1011101010110000110001110011101011101/1011101011110001000001100110101011101/1000001010110101000011001000001000001/1111111010101010101010101010101111111/0000000001100001111101110011000000000/0010011110010111100001100110110111110/0110000100100000000100101110100001101/0000001000001100110110111110111110111/1100100110000001110101110011110011111/1111101101001011000110100000110100110/0010110100000100111111000000111011000/1100101111110101010000100100000111010/0100010001100101111010111000001010010/0001001110100101100110000011001111011/0011100111001011101110010001010110001/0101011000101000101011010010010001110/1100000010000110101100101010001000101/0111101111111010000011101000110000010/0110110001111110000010110101000111011/0000001101110000100100101000100001100/1000100011110101011111100101001001100/1110101001000111001011001100000111010/0101010101110100101111001000000000110/1100011000110111110010101111000010010/0010110011001010000111111100010111111/1101011100011101110011001001111110111/0000000010000011100110101000100010100/1111111010101101001010010111101010001/1000001010110111111011001111100011000/1011101001001010010100100111111111101/1011101001111010110011100110011110010/1011101011111000101011010001100000111/1000001000011100110001000111100000101/1111111000111000111001110111000011111",
                "1111111011011011101100000110001111111/1000001011110010001010010101001000001/1011101010100100000100100010101011101/1011101001111001111000111010101011101/1011101001000111110111010000001011101/1000001011111100001010000001001000001/1111111010101010101010101010101111111/0000000010101000110100111010000000000/0011101010100001010111010000011100111/1111000101101001001101100111101000100/0110111110111010000000001000001000001/0101100111001000111100111010111010110/1001011011111101110000010110000010000/1011110101001101110110001001110010001/1010011001000011100110010010110001100/1101010000101100110011110001000011011/0111111000010011010000110101111001101/1010100110000010100111011000011111000/0011101110011110011101100100100111000/0101000011001111100101100011000001100/0001011001001100110101011110000110100/1111110000110111001011111100001110010/0110111011000110010010011110010111010/0001100010111100010110101100000000101/1000011111110001111101111010110001100/1100010100111101100110000001001001111/1010101110000001000100011001110100100/1011110010000011001110110101011110110/1011101010101011000101111111111110001/0000000011001010101111100001100011101/1111111000011011111100100001101010111/1000001001111110110010000110100010001/1011101011111100100010010001111111011/1011101010110011111010101111010111011/1011101011001110011101100111010110001/1000001001010101111000001110101001100/1111111000001110001111000001110101001",
                "1111111001011011101100000110001111111/1000001000101001010001001110001000001/1011101001001001101001001111001011101/1011101001111001111000111010101011101/1011101010011100101100001011001011101/1000001000010001100111101100101000001/1111111010101010101010101010101111111/0000000011110011101111100001000000000/0011001111001100111010111101111010000/1111000101101001001101100111101000100/1101101101100001011011010011010011010/1000000010100101010001010111010111011/1001011011111101110000010110000010000/0000100110010110101101010010101001010/0111111100101110001011111111011100001/1101010000101100110011110001000011011/1100101011001000001011101110100010110/0111000011101111001010110101110010101/0011101110011110011101100100100111000/1110010000010100111110111000011010111/1100111100100001011000110011101011001/1111110000110111001011111100001110010/1101101000011101001001000101001100001/1100000111010001111011000001101101000/1000011111110001111101111010110001100/0111000111100110111101011010010010100/0111001011101100101001110100011001001/1011110010000011001110110101011110110/0000111001110000011110100100111111010/0000000010100111000010001100100010000/1111111010011011111100100001101010111/1000001000100101101001011101100011010/1011101000010001001111111100111110110/1011101010110011111010101111010111011/1011101010010101000110111100001101010/1000001000111000010101100011000100001/1111111000001110001111000001110101001",
                "1111111000011100101011000001001111111/1000001010110101001101010010001000001/1011101000011100111100011010001011101/1011101001000001000000000010001011101/1011101000000000110000010111001011101/1000001010111011001101000110001000001/1111111010101010101010101010101111111/0000000010010000001100000010100000000/0000111101100110010000010111001100010/1000000010101110001010100000101111100/1110001110000010111000110000110000110/1101010111110000000100000010000010001/1110011100111010110111010001000101000/1100110010001010110001001110110101001/0010101001111011011110101010001001011/0101100000010100001011001001111011100/0000111111010100010111110010111110101/1101100001000101100000011111011000000/1011011110100110100101011100011111111/1101110011110111011101011011111001011/0110011110001011110010011001000001100/1000110111110000001100111011001001010/1110001011111110101010100110101111101/1001010010000100101110010100111000010/1111011000110110111010111101110110100/1011010011111010100001000110001110111/0010011110111001111100100001001100011/0011000010111011110110001101100110001/1100101101101100000010111000111111001/0000000010001101101000100110100010101/1111111010100011000100011001101010000/1000001011000110001010111110100010110/1011101010111011100101010110111110011/1011101001110100111101101000010000011/1011101001110110100101011111101110110/1000001001101101000000110110010001011/1111111001001001001000000110110010001",
                "1111111011101101011010110000101111111/1000001000110011001011010100001000001/1011101010100100000100100010101011101/1011101000011010011011011001001011101/1011101011000111110111010000001011101/1000001000111101001011000000001000001/1111111010101010101010101010101111111/0000000011101001110101111011000000000/0000011000100001010111010000001010101/1100100110001010101110000100001011000/0110111110111010000000001000001000001/0100100110001001111101111011111011110/1111101101001011000110100000110100110/1010110100001100110111001000110011001/1010011001000011100110010010110001100/1110110011001111010000010010100000111/0111111000010011010000110101111001101/1011100111000011100110011001011110000/0101011000101000101011010010010001110/0100000010001110100100100010000000100/0001011001001100110101011110000110100/1100010011010100101000011111101101110/0110111011000110010010011110010111010/0000100011111101010111101101000001101/1110101001000111001011001100000111010/1101010101111100100111000000001000111/1010101110000001000100011001110100100/1000010001100000101101010110111101010/1011101010101011000101111111111110001/0000000010001011101110100000100010101/1111111000101101001010010111101010001/1000001010111111110011000111100011001/1011101001111100100010010001111111011/1011101001010000011001001100110100111/1011101001001110011101100111010110001/1000001000010100111001001111101000100/1111111000111000111001110111000011111",
                "1111111001101101011010110000101111111/1000001000110101001101010010001000001/1011101010000000100000000110001011101/1011101010011010011011011001001011101/1011101001010101100101000010001011101/1000001000001101111011110000101000001/1111111010101010101010101010101111111/0000000001101111110011111101000000000/0001101100000101110011110100100001100/1100100110001010101110000100001011000/0100101100101000010010011010011010011/0100010110111001001101001011001011000/1111101101001011000110100000110100110/1100110010001010110001001110110101001/1110111101100111000010110110010101000/1110110011001111010000010010100000111/0101101010000001000010100111101011111/1011010111110011010110101001101110110/0101011000101000101011010010010001110/0010000100001000100010100100000110100/0101111101101000010001111010100010000/1100010011010100101000011111101101110/0100101001010100000000001100000101000/0000010011001101100111011101110001011/1110101001000111001011001100000111010/1011010011111010100001000110001110111/1110001010100101100000111101010000000/1000010001100000101101010110111101010/1001111000111001010111101101111110011/0000000010111011011110010000100010011/1111111010101101001010010111101010001/1000001000111001110101000001100011001/1011101011011000000110110101111111111/1011101011010000011001001100110100111/1011101001011100001111110101000100011/1000001000100100001001111111011000010/1111111000111000111001110111000011111",
                "1111111010111000001111100101101111111/1000001011001010110010101101101000001/1011101001010101110101010011001011101/1011101011100101100100100110101011101/1011101010000000110000010111001011101/1000001011110010000100001111001000001/1111111010101010101010101010101111111/0000000000010000001100000010100000000/0001001001010000100110100001100111011/0011010001110101010001111011110100111/0001111001111101000111001111001111001/1011100001000110110010110100110100111/1010111000011110010011110101100001100/0011000101110101001110110001001010110/1011101000110010010111100011000000010/0001000100110000101111101101011111000/0000111111010100010111110010111110101/0100100000001100101001010110010001001/0000001101111101111110000111000100100/1101110011110111011101011011111001011/0000101000111101000100101111110111010/0011100100101011010111100000010010001/0001111100000001010101011001010000010/1111100100110010011000100010001110100/1011111100010010011110011001010010000/0100100100000101011110111001110001000/1011011111110000110101101000000101010/0111100110011111010010101001000010101/1100101101101100000010111000111111001/0000000011000100100001101111100011100/1111111001111000011111000010101011011/1000001001000110001010111110100010110/1011101000001101010011100000111110101/1011101010101111100110110011001011000/1011101000001001011010100000010001001/1000001001011011110110000000100111101/1111111001101101101100100010010110101",
            ),
            scores = listOf(832, 1064, 1095, 907, 977, 1115, 950, 990),
        ),
        ReferenceCase(
            text = "DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER:XXXXXXXXXXXXXXXXXXXX",
            version = 6,
            level = DwQrEccLevel.H,
            masks = listOf(
                "11111110101101110001110010010101001111111/10000010000110110001011011000011101000001/10111010010101010000001010011111001011101/10111010110010111101000011000110001011101/10111010010011100110001110000011001011101/10000010011011100110000100101100001000001/11111110101010101010101010101010101111111/00000000011100101110001111110001100000000/00101110111101100001100010101100110001001/01001100001101101110000001010100100010011/10000011101111011010111110110000010110010/01000000110101100011000101010001011011011/00110010100000110100100100101101010000010/10001000011100000111111010011111010101011/00000011000011100100100001011000010011011/11010101111000101110111010001011000001101/11000011001000110111101000010100110101001/10010101100100010100101110000100001110100/10000110110101111111000010010011001111011/11100100011101100000011100101110000101111/01100011111100100101011101101011100011110/10100000000101101001100000111110000101111/10110111001101111011001100101000000011000/00001001110001101111000111110100001110111/00100111111101001001111001100001010101101/10001100010101101000011010100000000011100/01000010100101010010000011011010101011011/11001001100011110011111010110000000110001/01011111110001010001111100000111101100111/00111000111000101111111001110001100101110/10000111101001000110010111100101100001111/01101101101010110010010100010000111011101/10111010101110111001000100000111111111000/00000000101011111010010100011010100010110/11111110011000101011110111010101101011110/10000010110101000111101001011000100010001/10111010101100000011111110111000111110101/10111010000110100001101000010100011010111/10111010111110011010001101011011000101101/10000010000110110010110100000010010011011/11111110011011110011011111011010010010101",
                "11111110011000100100100111000000001111111/10000010110011100100001110010110101000001/10111010100000000101011111001010001011101/10111010100111101000010110010011001011101/10111010100110110011011011010110001011101/10000010101110110011010001111001001000001/11111110101010101010101010101010101111111/00000000001001111011011010100100100000000/00100111101000110100110111111001110111110/00011001011000111011010100000001110111001/11010110111010001111101011100101000011000/00010101100000110110010000000100001110001/01100111110101100001110001111000000101000/11011101001001010010101111001010000000001/01010110010110110001110100001101000110001/10000000101101111011101111011110010100111/10010110011101100010111101000001100000011/11000000110001000001111011010001011011110/11010011100000101010010111000110011010001/10110001001000110101001001111011010000101/00110110101001110000001000111110110110100/11110101010000111100110101101011010000101/11100010011000101110011001111101010110010/01011100100100111010010010100001011011101/01110010101000011100101100110100000000111/11011001000000111101001111110101010110110/00010111110000000111010110001111111110001/10011100110110100110101111100101010011011/00001010100100000100101001010010111001101/01101101101101111010101100100100110000100/11010010111100010011000010110000110100101/00111000111111100111000001000101101110111/11101111111011101100010001010010111110010/00000000111110101111000001001111100011100/11111110101101111110100010000000101010100/10000010100000010010111100001101100011011/10111010011001010110101011101101111111111/10111010010011110100111101000001001111101/10111010101011001111011000001110010000111/10000010010011100111100001010111000110001/11111110001110100110001010001111000111111",
                "11111110110101001001001010101101101111111/10000010100001110110011100000100101000001/10111010101101101000110010100111101011101/10111010010101111010000100000001001011101/10111010001011011110110110111011101011101/10000010111100100001000011101011001000001/11111110101010101010101010101010101111111/00000000111011101001001000110110100000000/00111010100101011001011010010100011100111/10001001001010101001000110010011100101011/10111011010111100010000110001000101110101/10000101110010100100000010010110011100011/00001010011000001100011100010101101000101/01001101011011000000111101011000010010011/00111011111011011100011001100000101011100/00010000111111101001111101001100000110101/11111011110000001111010000101100001101110/01010000100011010011101001000011001001100/10111110001101000111111010101011110111100/00100001011010100111011011101001000010111/01011011000100011101100101010011011011001/01100101000010101110100111111001000010111/10001111110101000011110100010000111011111/11001100110110101000000000110011001001111/00011111000101110001000001011001101101010/01001001010010101111011101100111000100100/01111010011101101010111011100010010011100/00001100100100110100111101110111000001001/01100111001001101001000100111111010100000/11111101111111101000111110110110100010110/10111111010001111110101111011101011001000/10101000101101110101010011010111111100101/10000010010110000001111100111111111111111/00000000101100111101010011011101100011110/11111110000000010011001111101101101011001/10000010010010000000101110011111100011001/10111010110100111011000110000000111110010/10111010100001100110101111010011011101111/10111010100110100010110101100011111101010/10000010000001110101110011000101010100011/11111110000011001011100111100010101010010",
                "11111110010101001001001010101101101111111/10000010010111000000101010110010001000001/10111010010110110011101001111100101011101/10111010010101111010000100000001001011101/10111010111101101000000000001101001011101/10000010000111111010011000110000001000001/11111110101010101010101010101010101111111/00000000101101011111111110000000000000000/00110011111110000010000001001111011010000/10001001001010101001000110010011100101011/00001111100001010100110000111110011000011/01011100101001111111011001001101000111000/00001010011000001100011100010101101000101/11111001101101110110001011101110100100101/11100010100000000111000010111011110000111/00010000111111101001111101001100000110101/01001111000110111001100110011010111011000/10001001111000001000110010011000010010111/10111110001101000111111010101011110111100/10010101101100010001101101011111110100001/10000010011111000110111110001000000000010/01100101000010101110100111111001000010111/00111011000011110101000010100110001101001/00010101101101110011011011101000010010100/00011111000101110001000001011001101101010/11111101100100011001101011010001110010010/10100011000110110001100000111001001000111/00001100100100110100111101110111000001001/11010011111111011111110010001001100010110/00100100100100110011100101101101111001101/10111111010001111110101111011101011001000/00011100011011000011100101100001001010011/01011011001101011010100111100100111110100/00000000101100111101010011011101100011110/11111110110110100101111001011011101011111/10000010001001011011110101000100100010010/10111010010100111011000110000000111110010/10111010110111010000011001100101101011001/10111010111101111001101110111000100110001/10000010000001110101110011000101010100011/11111110010101111101010001010100011100100",
                "11111110000100111000111011011100001111111/10000010110000000111101101110101001000001/10111010000011100110111100101001101011101/10111010011011110100001010001111001011101/10111010011010101111000111001010001011101/10000010101101010000110010011010101000001/11111110101010101010101010101010101111111/00000000110101100111000110111000100000000/00001111010100101000101011100101101100010/11111000111011011000110111100010010100101/00110111011001101100001000000110100000100/00001001111100101010001100011000010010010/01111011101001111101101101100100011001011/00111100101010110001001100101001100011101/10110111110101010010010111101110100101101/10011100110001100111110011000010001000100/10001010000001111110100001011101111100000/00100001010010100010011000110010111000010/00110010000011001001110100100101111001101/10101101010100101001010101100111001100110/00101010110101101100010100100010101010111/00010100110011011111010110001000110011001/00000011111011001101111010011110110101110/01000000111000100110001110111101000111110/01101110110100000000110000101000011100100/00111000100011011110101100010110110101010/11110110010011100100110101101100011101101/10000000101010111010110011111001001111000/00010110111000011000110101001110100101110/10001100001110011001001111000111010011000/00110011011111110000100001010011010111001/00100100100011111011011101011001110010100/11110011100111110000001101001110111110001/00000000111101001100100010101100100010000/11111110101110011101000001100011101011000/10000010111100001110100000010001100011000/10111010100101001010110111110001111111100/10111010010000010111011110100010101100001/10111010001000101100111011101101110011011/10000010001111111011111101001011011010010/11111110010010111010010110010011011011100",
                "11111110111000100100100111000000001111111/10000010010001100110001100010100101000001/10111010101101101000110010100111101011101/10111010001101000010111100111001101011101/10111010101011011110110110111011101011101/10000010001100110001010011111011001000001/11111110101010101010101010101010101111111/00000000101011111001011000100110100000000/00000110000101011001011010010100001010101/10110001110010010001111110101011011101100/10111011010111100010000110001000101110101/10010101100010110100010010000110001100001/01100111110101100001110001111000000101000/01011101001011010000101101001000000010001/00111011111011011100011001100000101011100/00101000000111010001000101110100111110010/11111011110000001111010000101100001101110/01000000110011000011111001010011011001110/11010011100000101010010111000110011010001/00110001001010110111001011111001010010101/01011011000100011101100101010011011011001/01011101111010010110011111000001111010000/10001111110101000011110100010000111011111/11011100100110111000010000100011011001101/01110010101000011100101100110100000000111/01011001000010111111001101110111010100110/01111010011101101010111011100010010011100/00110100011100001100000101001111111001110/01100111001001101001000100111111010100000/11101101101111111000101110100110110010100/11010010111100010011000010110000110100101/10111000111101100101000011000111101100111/10000010010110000001111100111111111111111/00000000110100000101101011100101100011001/11111110000000010011001111101101101011001/10000010100010010000111110001111100011011/10111010011001010110101011101101111111111/10111010010001110110111111000011001101101/10111010000110100010110101100011111101010/10000010011001001101001011111101101100100/11111110000011001011100111100010101010010",
                "11111110011000100100100111000000001111111/10000010010000000111101101110101001000001/10111010100100100001111011101110101011101/10111010101101000010111100111001101011101/10111010001111111010010010011111001011101/10000010000000111101011111110111001000001/11111110101010101010101010101010101111111/00000000001010011000111001000111000000000/00011011001100010000010011011101000001100/10110001110010010001111110101011011101100/10011111110011000110100010101100001010001/10011001101110111000011110001010000000000/01100111110101100001110001111000000101000/00111100101010110001001100101001100011101/01110010110010010101010000101001100010101/00101000000111010001000101110100111110010/11011111010100101011110100001000101001010/01001100111111001111110101011111010101111/11010011100000101010010111000110011010001/01010000101011010110101010011000110011001/00010010001101010100101100011010010010000/01011101111010010110011111000001111010000/10101011010001100111010000110100011111011/11010000101010110100011100101111010101100/01110010101000011100101100110100000000111/00111000100011011110101100010110110101010/00110011010100100011110010101011011010101/00110100011100001100000101001111111001110/01000011101101001101100000011011110000100/11100001100011110100100010101010111110101/11010010111100010011000010110000110100101/11011001011100000100100010100110001101011/11001011011111001000110101110110111110110/00000000110100000101101011100101100011001/11111110100100110111101011001001101011101/10000010001110011100110010000011100011010/10111010111001010110101011101101111111111/10111010110000010111011110100010101100001/10111010001111101011111100101010110100011/10000010011001001101001011111101101100100/11111110000111101111000011000110001110110",
                "11111110101101110001110010010101001111111/10000010101111111000010010001010101000001/10111010010001110100101110111011101011101/10111010110010111101000011000110001011101/10111010111010101111000111001010001011101/10000010111111000010100000001000101000001/11111110101010101010101010101010101111111/00000000010101100111000110111000100000000/00010010011001000101000110001000000111011/01001100001101101110000001010100100010011/11001010100110010011110111111001011111011/01100100010001000111100001110101111111111/00110010100000110100100100101101010000010/11000001010101001110110011010110011100010/00100111100111000000000101111100110111111/11010101111000101110111010001011000001101/10001010000001111110100001011101111100000/10110001000000110000001010100000101010000/10000110110101111111000010010011001111011/10101101010100101001010101100111001100110/01000111011000000001111001001111000111010/10100000000101101001100000111110000101111/11111110000100110010000101100001001010001/00101101010101001011100011010000101010011/00100111111101001001111001100001010101101/11000101011100100001010011101001001010101/01100110000001110110100111111110001111111/11001001100011110011111010110000000110001/00010110111000011000110101001110100101110/00011100011100001011011101010101000001010/10000111101001000110010111100101100001111/00100100100011111011011101011001110010100/10011110001010011101100000100011111111100/00000000101011111010010100011010100010110/11111110010001100010111110011100101010111/10000010010001100011001101111100100010101/10111010001100000011111110111000111110101/10111010101111101000100001011101010011110/10111010011010111110101001111111100001001/10000010000110110010110100000010010011011/11111110010010111010010110010011011011100",
            ),
            scores = listOf(1211, 1142, 1049, 1139, 1239, 1163, 1127, 1316),
        ),
    )

    /** The standard's published alphanumeric capacities, version 1-6 at each level. */
    private val CAPACITY = mapOf(
        "L1" to 25, "L2" to 47, "L3" to 77, "L4" to 114, "L5" to 154, "L6" to 195,
        "M1" to 20, "M2" to 38, "M3" to 61, "M4" to 90, "M5" to 122, "M6" to 154,
        "Q1" to 16, "Q2" to 29, "Q3" to 47, "Q4" to 67, "Q5" to 87, "Q6" to 108,
        "H1" to 10, "H2" to 20, "H3" to 35, "H4" to 50, "H5" to 64, "H6" to 84,
    )

    private val CHOSEN = listOf(
        ChosenCase("HELLO WORLD", DwQrEccLevel.Q, version = 1, size = 21, mask = 6),
        ChosenCase("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", DwQrEccLevel.Q, version = 3, size = 29, mask = 6),
        ChosenCase("DPW1:P:CMT0PROTOTYPE0001ABC:5QVC", DwQrEccLevel.Q, version = 3, size = 29, mask = 4),
        ChosenCase("DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER", DwQrEccLevel.Q, version = 4, size = 33, mask = 7),
        ChosenCase("DPW1:P:ABCDEFGH:0000", DwQrEccLevel.M, version = 1, size = 21, mask = 0),
        ChosenCase("DPW1:P:ABCDEFGH:0000", DwQrEccLevel.H, version = 2, size = 25, mask = 0),
        ChosenCase("DPW1:P:ABCDEFGH:0000", DwQrEccLevel.L, version = 1, size = 21, mask = 0),
        ChosenCase("DPW1:P:ABCDEFGH:0000", DwQrEccLevel.Q, version = 2, size = 25, mask = 6),
        ChosenCase("0", DwQrEccLevel.H, version = 1, size = 21, mask = 0),
        ChosenCase("\$%*+-./: ", DwQrEccLevel.H, version = 1, size = 21, mask = 5),
        ChosenCase("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 5, size = 37, mask = 6),
        ChosenCase("DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER:XXXX", DwQrEccLevel.H, version = 5, size = 37, mask = 0),
        ChosenCase("DPW1:A:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:M9ER:XXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 6, size = 41, mask = 2),
        ChosenCase("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD:XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 6, size = 41, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 1, size = 21, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 2, size = 25, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 2, size = 25, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 3, size = 29, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 3, size = 29, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 4, size = 33, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 4, size = 33, mask = 4),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 5, size = 37, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 5, size = 37, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 6, size = 41, mask = 4),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.L, version = 6, size = 41, mask = 4),
        ChosenCase("XXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 1, size = 21, mask = 1),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 2, size = 25, mask = 4),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 2, size = 25, mask = 1),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 3, size = 29, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 3, size = 29, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 4, size = 33, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 4, size = 33, mask = 1),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 5, size = 37, mask = 4),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 5, size = 37, mask = 5),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 6, size = 41, mask = 6),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.M, version = 6, size = 41, mask = 6),
        ChosenCase("XXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 1, size = 21, mask = 1),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 2, size = 25, mask = 7),
        ChosenCase("XXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 2, size = 25, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 3, size = 29, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 3, size = 29, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 4, size = 33, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 4, size = 33, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 5, size = 37, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 5, size = 37, mask = 3),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 6, size = 41, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.Q, version = 6, size = 41, mask = 4),
        ChosenCase("XXXXXXXXXX", DwQrEccLevel.H, version = 1, size = 21, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 2, size = 25, mask = 7),
        ChosenCase("XXXXXXXXXXX", DwQrEccLevel.H, version = 2, size = 25, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 3, size = 29, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 3, size = 29, mask = 2),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 4, size = 33, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 4, size = 33, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 5, size = 37, mask = 0),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 5, size = 37, mask = 2),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 6, size = 41, mask = 1),
        ChosenCase("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", DwQrEccLevel.H, version = 6, size = 41, mask = 0),
    )

    /** The one case whose FINAL matrix — mask already chosen and applied — is frozen in full. */
    private val ENCODED_MATRIX_TEXT = "0"
    private val ENCODED_MATRIX_LEVEL = DwQrEccLevel.H
    private val ENCODED_MATRIX =
        "111111101100101111111/100000100101001000001/101110100110001011101/101110101010101011101/101110100000101011101/100000100110101000001/111111101010101111111/000000000101100000000/001011101000110001001/001011010001011000110/000110111110000010001/110111000110110000110/010111101110111010101/000000001000011101010/111111100001010101100/100000101010001111010/101110101111000101101/101110100101110000110/101110101101011010001/100000100100000000111/111111100110010010101"

    private val REFUSALS = listOf(
        RefusalCase(
            text = "",
            level = DwQrEccLevel.Q,
            reason = "EMPTY",
            message = "There is nothing to encode.",
        ),
        RefusalCase(
            text = "lower case",
            level = DwQrEccLevel.Q,
            reason = "NOT_ALPHANUMERIC",
            message = "This code contains characters a compact QR symbol cannot carry. Codes are upper-case letters, digits and the punctuation the standard allows.",
        ),
        RefusalCase(
            text = "DPW1:A:ABC#DEF",
            level = DwQrEccLevel.Q,
            reason = "NOT_ALPHANUMERIC",
            message = "This code contains characters a compact QR symbol cannot carry. Codes are upper-case letters, digits and the punctuation the standard allows.",
        ),
        RefusalCase(
            text = "ram@example.org",
            level = DwQrEccLevel.Q,
            reason = "NOT_ALPHANUMERIC",
            message = "This code contains characters a compact QR symbol cannot carry. Codes are upper-case letters, digits and the punctuation the standard allows.",
        ),
        RefusalCase(
            text = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            level = DwQrEccLevel.Q,
            reason = "TOO_LONG",
            message = "This code is 109 characters, and the largest symbol this app draws holds 108.",
        ),
        RefusalCase(
            text = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            level = DwQrEccLevel.Q,
            reason = "TOO_LONG",
            message = "This code is 200 characters, and the largest symbol this app draws holds 108.",
        ),
        RefusalCase(
            text = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            level = DwQrEccLevel.L,
            reason = "TOO_LONG",
            message = "This code is 196 characters, and the largest symbol this app draws holds 195.",
        ),
        RefusalCase(
            text = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            level = DwQrEccLevel.M,
            reason = "TOO_LONG",
            message = "This code is 155 characters, and the largest symbol this app draws holds 154.",
        ),
        RefusalCase(
            text = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            level = DwQrEccLevel.H,
            reason = "TOO_LONG",
            message = "This code is 85 characters, and the largest symbol this app draws holds 84.",
        ),
    )

    private val ALPHANUMERIC = listOf(
        AlphanumericCase("HELLO WORLD", true),
        AlphanumericCase("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", true),
        AlphanumericCase("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:", true),
        AlphanumericCase("lower", false),
        AlphanumericCase("a", false),
        AlphanumericCase("#", false),
        AlphanumericCase("@", false),
        AlphanumericCase("!", false),
        AlphanumericCase(";", false),
        AlphanumericCase(" ", true),
        AlphanumericCase(" ", true),
        AlphanumericCase("\u0905", false),
        AlphanumericCase("\uD83D\uDE00", false),
        AlphanumericCase("A\uD83D\uDE00B", false),
        AlphanumericCase("\uD800", false),
        AlphanumericCase("", true),
    )

    private val SVG = listOf(
        SvgCase(
            text = "DPW1:P:ABCDEFGH:0000",
            level = DwQrEccLevel.M,
            quiet = 4,
            extent = 29,
            path =
                "M4 4h7v1h-7zM15 4h2v1h-2zM18 4h7v1h-7zM4 5h1v1h-1zM10 5h1v1h-1zM12 5h2v1h-2zM18 5h1v1h-1zM24 5h1v1h-1zM4 6h1v1h-1zM6 6h3v1h-3zM10 6h1v1h-1zM13 6h1v1h-1zM15 6h1v1h-1zM18 6h1v1h-1zM20 6h3v1h-3zM24 6h1v1h-1zM4 7h1v1h-1zM6 7h3v1h-3zM10 7h1v1h-1zM14 7h1v1h-1zM16 7h1v1h-1zM18 7h1v1h-1zM20 7h3v1h-3zM24 7h1v1h-1zM4 8h1v1h-1zM6 8h3v1h-3zM10 8h1v1h-1zM12 8h3v1h-3zM16 8h1v1h-1zM18 8h1v1h-1zM20 8h3v1h-3zM24 8h1v1h-1zM4 9h1v1h-1zM10 9h1v1h-1zM14 9h2v1h-2zM18 9h1v1h-1zM24 9h1v1h-1zM4 10h7v1h-7zM12 10h1v1h-1zM14 10h1v1h-1zM16 10h1v1h-1zM18 10h7v1h-7zM13 11h1v1h-1zM15 11h1v1h-1zM4 12h1v1h-1zM6 12h1v1h-1zM8 12h1v1h-1zM10 12h1v1h-1zM14 12h1v1h-1zM16 12h1v1h-1zM20 12h1v1h-1zM23 12h1v1h-1zM4 13h1v1h-1zM6 13h4v1h-4zM13 13h3v1h-3zM17 13h3v1h-3zM21 13h4v1h-4zM4 14h1v1h-1zM7 14h1v1h-1zM10 14h3v1h-3zM16 14h1v1h-1zM19 14h3v1h-3zM24 14h1v1h-1zM6 15h4v1h-4zM12 15h4v1h-4zM18 15h1v1h-1zM20 15h1v1h-1zM23 15h2v1h-2zM4 16h3v1h-3zM9 16h4v1h-4zM14 16h1v1h-1zM16 16h1v1h-1zM22 16h3v1h-3zM12 17h1v1h-1zM14 17h2v1h-2zM17 17h2v1h-2zM20 17h2v1h-2zM23 17h2v1h-2zM4 18h7v1h-7zM13 18h2v1h-2zM16 18h1v1h-1zM19 18h1v1h-1zM21 18h1v1h-1zM24 18h1v1h-1zM4 19h1v1h-1zM10 19h1v1h-1zM14 19h2v1h-2zM18 19h1v1h-1zM22 19h3v1h-3zM4 20h1v1h-1zM6 20h3v1h-3zM10 20h1v1h-1zM12 20h3v1h-3zM16 20h1v1h-1zM18 20h1v1h-1zM20 20h2v1h-2zM4 21h1v1h-1zM6 21h3v1h-3zM10 21h1v1h-1zM15 21h1v1h-1zM18 21h1v1h-1zM20 21h1v1h-1zM23 21h1v1h-1zM4 22h1v1h-1zM6 22h3v1h-3zM10 22h1v1h-1zM12 22h1v1h-1zM17 22h2v1h-2zM20 22h3v1h-3zM24 22h1v1h-1zM4 23h1v1h-1zM10 23h1v1h-1zM14 23h2v1h-2zM17 23h4v1h-4zM23 23h2v1h-2zM4 24h7v1h-7zM12 24h8v1h-8zM21 24h1v1h-1zM24 24h1v1h-1z",
        ),
        SvgCase(
            text = "HELLO WORLD",
            level = DwQrEccLevel.Q,
            quiet = 0,
            extent = 21,
            path =
                "M0 0h7v1h-7zM11 0h1v1h-1zM14 0h7v1h-7zM0 1h1v1h-1zM6 1h1v1h-1zM8 1h2v1h-2zM12 1h1v1h-1zM14 1h1v1h-1zM20 1h1v1h-1zM0 2h1v1h-1zM2 2h3v1h-3zM6 2h1v1h-1zM9 2h1v1h-1zM11 2h2v1h-2zM14 2h1v1h-1zM16 2h3v1h-3zM20 2h1v1h-1zM0 3h1v1h-1zM2 3h3v1h-3zM6 3h1v1h-1zM8 3h5v1h-5zM14 3h1v1h-1zM16 3h3v1h-3zM20 3h1v1h-1zM0 4h1v1h-1zM2 4h3v1h-3zM6 4h1v1h-1zM8 4h2v1h-2zM11 4h1v1h-1zM14 4h1v1h-1zM16 4h3v1h-3zM20 4h1v1h-1zM0 5h1v1h-1zM6 5h1v1h-1zM9 5h1v1h-1zM12 5h1v1h-1zM14 5h1v1h-1zM20 5h1v1h-1zM0 6h7v1h-7zM8 6h1v1h-1zM10 6h1v1h-1zM12 6h1v1h-1zM14 6h7v1h-7zM8 7h2v1h-2zM11 7h2v1h-2zM1 8h1v1h-1zM3 8h4v1h-4zM8 8h2v1h-2zM12 8h3v1h-3zM16 8h2v1h-2zM19 8h1v1h-1zM0 9h1v1h-1zM2 9h4v1h-4zM7 9h1v1h-1zM12 9h4v1h-4zM17 9h3v1h-3zM2 10h1v1h-1zM4 10h1v1h-1zM6 10h2v1h-2zM11 10h1v1h-1zM14 10h2v1h-2zM0 11h1v1h-1zM2 11h2v1h-2zM5 11h1v1h-1zM9 11h1v1h-1zM11 11h2v1h-2zM16 11h2v1h-2zM0 12h2v1h-2zM3 12h8v1h-8zM12 12h3v1h-3zM16 12h5v1h-5zM8 13h1v1h-1zM12 13h1v1h-1zM15 13h1v1h-1zM17 13h1v1h-1zM0 14h7v1h-7zM9 14h2v1h-2zM13 14h2v1h-2zM17 14h4v1h-4zM0 15h1v1h-1zM6 15h1v1h-1zM8 15h1v1h-1zM10 15h1v1h-1zM13 15h1v1h-1zM16 15h1v1h-1zM18 15h3v1h-3zM0 16h1v1h-1zM2 16h3v1h-3zM6 16h1v1h-1zM8 16h2v1h-2zM11 16h1v1h-1zM14 16h1v1h-1zM18 16h3v1h-3zM0 17h1v1h-1zM2 17h3v1h-3zM6 17h1v1h-1zM8 17h1v1h-1zM10 17h3v1h-3zM16 17h1v1h-1zM18 17h1v1h-1zM0 18h1v1h-1zM2 18h3v1h-3zM6 18h1v1h-1zM9 18h1v1h-1zM14 18h1v1h-1zM19 18h2v1h-2zM0 19h1v1h-1zM6 19h1v1h-1zM8 19h3v1h-3zM13 19h3v1h-3zM18 19h2v1h-2zM0 20h7v1h-7zM9 20h1v1h-1zM11 20h1v1h-1zM19 20h1v1h-1z",
        ),
        SvgCase(
            text = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD",
            level = DwQrEccLevel.Q,
            quiet = 4,
            extent = 37,
            path =
                "M4 4h7v1h-7zM13 4h1v1h-1zM15 4h1v1h-1zM17 4h3v1h-3zM22 4h2v1h-2zM26 4h7v1h-7zM4 5h1v1h-1zM10 5h1v1h-1zM12 5h1v1h-1zM14 5h2v1h-2zM19 5h2v1h-2zM24 5h1v1h-1zM26 5h1v1h-1zM32 5h1v1h-1zM4 6h1v1h-1zM6 6h3v1h-3zM10 6h1v1h-1zM13 6h3v1h-3zM17 6h4v1h-4zM22 6h1v1h-1zM24 6h1v1h-1zM26 6h1v1h-1zM28 6h3v1h-3zM32 6h1v1h-1zM4 7h1v1h-1zM6 7h3v1h-3zM10 7h1v1h-1zM12 7h1v1h-1zM15 7h3v1h-3zM19 7h1v1h-1zM22 7h3v1h-3zM26 7h1v1h-1zM28 7h3v1h-3zM32 7h1v1h-1zM4 8h1v1h-1zM6 8h3v1h-3zM10 8h1v1h-1zM12 8h1v1h-1zM14 8h1v1h-1zM16 8h1v1h-1zM20 8h4v1h-4zM26 8h1v1h-1zM28 8h3v1h-3zM32 8h1v1h-1zM4 9h1v1h-1zM10 9h1v1h-1zM16 9h1v1h-1zM19 9h4v1h-4zM24 9h1v1h-1zM26 9h1v1h-1zM32 9h1v1h-1zM4 10h7v1h-7zM12 10h1v1h-1zM14 10h1v1h-1zM16 10h1v1h-1zM18 10h1v1h-1zM20 10h1v1h-1zM22 10h1v1h-1zM24 10h1v1h-1zM26 10h7v1h-7zM12 11h1v1h-1zM14 11h1v1h-1zM17 11h1v1h-1zM19 11h2v1h-2zM23 11h1v1h-1zM5 12h1v1h-1zM7 12h4v1h-4zM12 12h1v1h-1zM15 12h2v1h-2zM19 12h1v1h-1zM22 12h2v1h-2zM25 12h2v1h-2zM28 12h2v1h-2zM31 12h1v1h-1zM6 13h1v1h-1zM8 13h2v1h-2zM11 13h1v1h-1zM14 13h3v1h-3zM18 13h1v1h-1zM20 13h1v1h-1zM24 13h2v1h-2zM27 13h1v1h-1zM30 13h1v1h-1zM4 14h2v1h-2zM7 14h1v1h-1zM10 14h2v1h-2zM13 14h1v1h-1zM16 14h3v1h-3zM20 14h3v1h-3zM24 14h2v1h-2zM31 14h1v1h-1zM5 15h2v1h-2zM8 15h1v1h-1zM11 15h1v1h-1zM14 15h1v1h-1zM19 15h4v1h-4zM25 15h1v1h-1zM27 15h1v1h-1zM29 15h1v1h-1zM6 16h2v1h-2zM10 16h1v1h-1zM12 16h3v1h-3zM16 16h2v1h-2zM19 16h4v1h-4zM27 16h4v1h-4zM32 16h1v1h-1zM4 17h1v1h-1zM7 17h1v1h-1zM9 17h1v1h-1zM14 17h9v1h-9zM24 17h6v1h-6zM5 18h1v1h-1zM10 18h3v1h-3zM15 18h1v1h-1zM17 18h2v1h-2zM20 18h2v1h-2zM24 18h1v1h-1zM30 18h1v1h-1zM32 18h1v1h-1zM4 19h1v1h-1zM6 19h2v1h-2zM9 19h1v1h-1zM12 19h2v1h-2zM16 19h1v1h-1zM19 19h1v1h-1zM21 19h2v1h-2zM24 19h4v1h-4zM29 19h2v1h-2zM32 19h1v1h-1zM4 20h1v1h-1zM6 20h1v1h-1zM8 20h3v1h-3zM13 20h2v1h-2zM17 20h5v1h-5zM24 20h2v1h-2zM28 20h1v1h-1zM30 20h3v1h-3zM4 21h1v1h-1zM7 21h1v1h-1zM14 21h5v1h-5zM20 21h6v1h-6zM27 21h3v1h-3zM4 22h2v1h-2zM8 22h4v1h-4zM14 22h1v1h-1zM17 22h3v1h-3zM21 22h5v1h-5zM27 22h2v1h-2zM31 22h1v1h-1zM4 23h2v1h-2zM7 23h2v1h-2zM12 23h1v1h-1zM22 23h2v1h-2zM26 23h1v1h-1zM28 23h1v1h-1zM32 23h1v1h-1zM4 24h3v1h-3zM8 24h3v1h-3zM13 24h1v1h-1zM16 24h5v1h-5zM22 24h1v1h-1zM24 24h6v1h-6zM31 24h2v1h-2zM12 25h1v1h-1zM14 25h3v1h-3zM18 25h2v1h-2zM24 25h1v1h-1zM28 25h1v1h-1zM30 25h1v1h-1zM32 25h1v1h-1zM4 26h7v1h-7zM18 26h5v1h-5zM24 26h1v1h-1zM26 26h1v1h-1zM28 26h2v1h-2zM32 26h1v1h-1zM4 27h1v1h-1zM10 27h1v1h-1zM12 27h1v1h-1zM14 27h1v1h-1zM16 27h2v1h-2zM20 27h2v1h-2zM23 27h2v1h-2zM28 27h3v1h-3zM4 28h1v1h-1zM6 28h3v1h-3zM10 28h1v1h-1zM12 28h4v1h-4zM18 28h5v1h-5zM24 28h5v1h-5zM4 29h1v1h-1zM6 29h3v1h-3zM10 29h1v1h-1zM12 29h1v1h-1zM15 29h4v1h-4zM20 29h2v1h-2zM25 29h1v1h-1zM27 29h1v1h-1zM30 29h2v1h-2zM4 30h1v1h-1zM6 30h3v1h-3zM10 30h1v1h-1zM13 30h1v1h-1zM15 30h1v1h-1zM18 30h1v1h-1zM20 30h1v1h-1zM25 30h1v1h-1zM29 30h1v1h-1zM31 30h2v1h-2zM4 31h1v1h-1zM10 31h1v1h-1zM12 31h2v1h-2zM16 31h1v1h-1zM19 31h1v1h-1zM21 31h2v1h-2zM25 31h6v1h-6zM4 32h7v1h-7zM13 32h1v1h-1zM15 32h1v1h-1zM17 32h1v1h-1zM21 32h1v1h-1zM24 32h1v1h-1zM27 32h5v1h-5z",
        ),
    )
}
