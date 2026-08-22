"""Guards the one thing that keeps the server's report and the phone's report the same document.

``report_docx.py`` and ``android/.../report/DocxWriter.kt`` are the same algorithm written twice,
in two languages, because the phone must produce a .docx with no network and no third-party
library. Nothing in a compiler stops the two drifting: a constant edited on one side and not the
other produces two files that both open, both look plausible, and disagree — and the disagreement
surfaces months later as "the report I generated in the field does not match the one the office
downloaded".

These tests read the KOTLIN SOURCE as text and assert that the values which must match the Python
actually do. That is a blunt instrument and deliberately so: a parity test that imported and ran
the Kotlin would need a JVM in CI, and the failure it is defending against is not a logic bug, it
is somebody changing 45.0 to 40.0 in one file. Reading the source catches exactly that, in the
place it happens, with a message naming the other file.

THE LAST SECTION OF THIS FILE IS DIFFERENT IN KIND and worth reading before adding to the rest.
Everything above it is one assertion per defect, written after the defect, and most of it reads
only one side — a substring hunted for in the Kotlin, a constant looked up in the Python. Those
stop the line that was already broken and nothing else. The shape mirrors at the end derive what
they compare FROM THE CODE ON BOTH SIDES — every millimetre in the paired layout methods, every
block each of the four renderers dispatches, the page label all five surfaces print (the four
file renderers and the web preview), the loop both PDF renderers settle their contents with — so
they fail whichever side drifts, and they fail on the next instance rather than on the last one.
A new assertion belongs there if it can be made two-sided, and only up here if it genuinely
cannot.

Skipped, not failed, when the Android tree is absent — the backend is deployed without it.
"""

import re
import zipfile
from io import BytesIO
from pathlib import Path

import pytest

from app.services import report_docx, report_pdf
from app.services.report_model import ReportTheme
from tests.test_report_docx import _png

_ANDROID = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/com/designprototype/workshop/report"
)

#: The web report preview. Not a file renderer — it draws the report into the DOM so a designer can
#: proof it before downloading — but it is a surface that prints the same running foot, and the one
#: the page-label fix was measured against and still missed. Read as text for the same reason the
#: Kotlin is: running it would need a browser, and the drift being defended against is somebody
#: editing a label on three surfaces out of five.
_FRONTEND = Path(__file__).resolve().parents[2] / "frontend/components/designworkshop/report"

pytestmark = pytest.mark.skipif(
    not _ANDROID.is_dir(), reason="the Android source tree is not present in this checkout"
)


def _kotlin(name: str) -> str:
    """The Kotlin source, normalised so a literal comparison is about content not syntax.

    Kotlin escapes the double quotes inside an XML string literal and splits a long one across
    concatenated lines. Neither is a difference in what the writer emits, and a test that fails
    on either is a test that gets deleted the first time somebody reformats the file. Stripping
    the backslashes and the inter-literal joins compares the XML the two writers produce.
    """
    text = (_ANDROID / name).read_text(encoding="utf-8")
    text = text.replace('\\"', '"')
    # `"...<w:tblW/>" +\n        "<w:tblBorders>...` is one string; join the halves.
    return re.sub(r'"\s*\+\s*\n?\s*"', "", text)


@pytest.fixture(scope="module")
def model_kt() -> str:
    return _kotlin("ReportModel.kt")


@pytest.fixture(scope="module")
def docx_kt() -> str:
    return _kotlin("DocxWriter.kt")


@pytest.fixture(scope="module")
def pdf_kt() -> str:
    return _kotlin("PdfWriter.kt")


def _kotlin_function(source: str, name: str) -> str:
    """The body of one Kotlin ``fun``, brace-matched.

    The alternative — slicing from one function's name to the next one's, which several of the
    older assertions in this file do — silently reads the wrong span the moment somebody moves a
    function, and a parity test that reads the wrong span passes for the wrong reason.
    """
    start = source.index(f"fun {name}(")
    open_brace = source.index("{", source.index(")", start))
    depth = 0
    for i in range(open_brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    raise AssertionError(f"PdfWriter.kt: fun {name} has no closing brace")


def _strip_comments(source: str, *, kotlin: bool) -> str:
    """``source`` with its comments removed and its string literals left alone.

    ── WHY NOT ``line.split("#")[0]`` ──────────────────────────────────────────────────────────

    Because a ``#`` is also how this codebase writes a colour, and a ``//`` is how it writes a
    URL. Cutting the line at the first marker cuts ``self.c.setFillColor("#1a3b5c")`` in half,
    and the caller below then reads a truncated method — silently, because a truncated method is
    still a string. What comes out of that is a set of distances missing everything after the
    colour, and the failure it produces blames the OTHER renderer for not having them.

    No line in either renderer carries both a marker inside a literal and a ``* MM`` today; this
    is not a bug being fixed, it is a trap being taken out of a helper that three tests now
    depend on. The Python side goes through :mod:`tokenize`, which knows what a string is by
    construction; the Kotlin has no tokenizer to hand, so it gets a scanner that steps over an
    ordinary string, a raw (triple-quoted) string and a character literal before it looks for a
    line comment or a block one.
    """
    if not kotlin:
        import io
        import textwrap
        import tokenize

        # Dedented because ``inspect.getsource`` of a method is indented by its class and
        # ``tokenize`` refuses a block that starts indented.
        body = textwrap.dedent(source)
        cut: dict[int, int] = {}
        for token in tokenize.generate_tokens(io.StringIO(body).readline):
            if token.type == tokenize.COMMENT:
                row, col = token.start
                cut[row] = min(cut.get(row, col), col)
        return "\n".join(
            line[:cut[n]] if n in cut else line
            for n, line in enumerate(body.splitlines(), 1)
        )

    out: list[str] = []
    i, end = 0, len(source)
    while i < end:
        if source.startswith('"""', i):
            close = source.find('"""', i + 3)
            close = end if close < 0 else close + 3
            out.append(source[i:close])
            i = close
        elif source[i] in '"\'':
            quote = source[i]
            j = i + 1
            while j < end and source[j] != quote:
                j += 2 if source[j] == "\\" else 1
            j = min(j + 1, end)
            out.append(source[i:j])
            i = j
        elif source.startswith("//", i):
            close = source.find("\n", i)
            i = end if close < 0 else close
        elif source.startswith("/*", i):
            close = source.find("*/", i + 2)
            i = end if close < 0 else close + 2
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def _mm_distances(source: str, *, kotlin: bool) -> set[float]:
    """Every distance the source expresses in millimetres, as a set of numbers.

    ── WHY A GENERAL EXTRACTOR AND NOT MORE ``assert "45" in pdf_kt`` ──────────────────────────

    Almost everything the two PDF renderers disagree about is a distance. The three defects this
    file was opened for were 6 mm of clearance, 1.2 mm of heading rule and a flat 6 mm reservation
    that should have been 10.1 — and the tests that were supposed to hold them were substring
    searches for the digits, which pass on any file long enough to contain them somewhere.

    So the numbers are read out of the code instead: every operand of ``* MM``, including the
    ones written as a parenthesised choice (``(4.5 if level > 1 else 6.5) * MM`` on one side,
    ``(if (level > 1) 4.5f else 6.5f) * MM`` on the other, both yielding 4.5 and 6.5 and the 1
    they compare the level against). Comments are stripped first by :func:`_strip_comments` —
    both files explain their geometry in prose full of millimetre figures, and reading those
    would compare the commentary rather than the code.

    A SET rather than a multiset, deliberately. The two files legitimately compute the same
    distance a different number of times — the server writes ``2.6 * MM`` twice for the two ends
    of the header rule and the Kotlin binds it once and reuses it — and counting occurrences
    would fail on that while catching nothing a set does not.

    WHAT A SET CANNOT SEE, and the caller's docstring says so too: an EXCHANGE inside one method.
    Swap the 4.5 and the 6.5 of ``(if (level > 1) 4.5f else 6.5f) * MM`` on one side only and the
    set is unchanged, so a passing comparison means the two methods use the same distances — not
    that they use them in the same places. Nothing cheap fixes that (matching them up would mean
    parsing two languages into a common tree), and it is the narrower failure: the ones this has
    to catch are a number edited on one side, which changes the set.
    """
    import re

    stripped = _strip_comments(source, kotlin=kotlin)
    number = re.compile(r"\d+(?:\.\d+)?")
    found: set[float] = set()
    for match in re.finditer(r"\*\s*MM\b", stripped):
        head = stripped[:match.start()].rstrip()
        if head.endswith(")"):
            # A parenthesised expression multiplied by MM: take the whole balanced group.
            depth = 0
            i = len(head) - 1
            while i >= 0:
                if head[i] == ")":
                    depth += 1
                elif head[i] == "(":
                    depth -= 1
                    if depth == 0:
                        break
                i -= 1
            fragment = head[i:]
        else:
            literal = re.search(r"(\d+(?:\.\d+)?)f?$", head)
            fragment = literal.group(1) if literal else ""
        found.update(float(n) for n in number.findall(fragment))
    return found


#: The layout methods that are the same method written twice, server name against handset name.
#:
#: A pair that stops existing on either side fails :func:`_kotlin_function` or ``getattr``, which
#: is the point: a renderer that grows a block the other has not is the shape of every divergence
#: this file exists for.
#:
#: TWENTY PAIRS IS NOT TWENTY COVERED METHODS. ``_image_box``/``imageBox`` and
#: ``_draw_image``/``drawImage`` contain no ``MM`` token, so the distance mirror compares two
#: empty sets for them and pins nothing at all. They are listed anyway because the existence
#: check above is worth having on its own, but do not read the length of this tuple as coverage —
#: the measured figures are in the mirror's own docstring.
_LAYOUT_PAIRS = (
    ("_new_page", "newPage"),
    ("_cut_row", "cutRow"),
    ("_draw_furniture", "drawFurniture"),
    ("_caption", "caption"),
    ("_image_box", "imageBox"),
    ("_draw_image", "drawImage"),
    ("_simple_grid", "simpleGrid"),
    ("_place_tall_grid_row", "placeTallGridRow"),
    ("_block_cover", "blockCover"),
    ("_block_toc", "blockToc"),
    ("_block_heading", "blockHeading"),
    ("_block_paragraph", "blockParagraph"),
    ("_block_bullets", "blockBullets"),
    ("_block_table", "blockTable"),
    ("_block_image", "blockImage"),
    ("_block_image_grid", "blockImageGrid"),
    ("_block_figure", "blockFigure"),
    ("_block_metrics", "blockMetrics"),
    ("_block_callout", "blockCallout"),
    ("_block_signatures", "blockSignatures"),
)


# --------------------------------------------------------------------------------------
# The model
# --------------------------------------------------------------------------------------


def test_both_models_declare_the_same_block_types(model_kt):
    """A block the phone cannot render is a section of the report that silently disappears."""
    from app.services import report_model

    python_blocks = {
        name for name in dir(report_model)
        if name.endswith("Block") and name != "Block"
    }
    for name in python_blocks:
        assert name in model_kt, f"{name} is in report_model.py but not in ReportModel.kt"


def test_both_models_declare_the_same_scripts(model_kt):
    from app.services.report_model import Script

    for script in Script:
        assert script.value in model_kt, f"Script.{script.value} missing from ReportModel.kt"


def test_both_models_declare_the_same_paragraph_styles(model_kt):
    from app.services.report_model import ParaStyle

    for style in ParaStyle:
        assert style.value in model_kt, f"ParaStyle.{style.value} missing from ReportModel.kt"


def test_the_script_block_ranges_match(model_kt):
    """A range that differs by one block puts a craft's local name in the wrong font."""
    from app.services.report_model import _SCRIPT_RANGES

    for lo, hi, script in _SCRIPT_RANGES:
        assert f"0x{lo:04X}" in model_kt or f"0x{lo:04x}" in model_kt, \
            f"{script.value} lower bound 0x{lo:04X} missing from ReportModel.kt"
        assert f"0x{hi:04X}" in model_kt or f"0x{hi:04x}" in model_kt, \
            f"{script.value} upper bound 0x{hi:04X} missing from ReportModel.kt"


def test_the_table_width_tolerance_matches(model_kt):
    """Python raises above 0.5; a laxer Kotlin would let a bad template through on the phone
    and produce a table wider than the page that Word silently rescales."""
    assert "100.0) > 0.5" in model_kt or "100.0) > 0.5f" in model_kt


# --------------------------------------------------------------------------------------
# The DOCX writer — the constants that make Word open the file
# --------------------------------------------------------------------------------------


def test_the_main_document_part_type_matches(docx_kt):
    """The .docx FILE mime on the main document PART passes every structural check and is
    refused by Word with no explanation. Both writers must name the PART type."""
    assert ".document.main+xml" in docx_kt
    # And neither may declare the file type on that part.
    assert 'PartName="/word/document.xml" ContentType="' + report_docx.DOCX_MIME not in docx_kt


def test_the_schema_sequence_orderings_match(docx_kt):
    """CT_TrPr and CT_TblPr are sequences, not sets. Reversed, the part is schema-invalid.

    Checked on the EMISSION order rather than on where the strings appear in the file: the
    Kotlin builds the borders into a local and interpolates it, so the literal ``<w:tblBorders>``
    is defined above the ``<w:tblPr>`` that uses it. What matters is which one is concatenated
    first.
    """
    assert "<w:cantSplit/><w:tblHeader/>" in docx_kt, \
        "cantSplit must precede tblHeader in DocxWriter.kt, as it does in report_docx.py"

    tbl_pr = docx_kt[docx_kt.index("<w:tbl><w:tblPr>"):]
    tbl_pr = tbl_pr[:tbl_pr.index("</w:tblPr>")]
    # Borders reach the string either literally or through the local that holds them.
    borders_at = min(
        (tbl_pr.index(token) for token in ("<w:tblBorders>", "borderXml") if token in tbl_pr),
        default=-1,
    )
    assert borders_at >= 0, "DocxWriter.kt emits no table borders at all"
    assert borders_at < tbl_pr.index("<w:tblLayout"), \
        "tblBorders must be concatenated before tblLayout in DocxWriter.kt"


def test_the_core_properties_order_matches(docx_kt):
    """CT_CoreProperties is alphabetical by local name, which is not how anyone writes them."""
    order = ["<cp:category>", "<dcterms:created", "<dc:creator>", "<cp:lastModifiedBy>",
             "<dcterms:modified", "<dc:subject>", "<dc:title>"]
    positions = [docx_kt.index(tag) for tag in order if tag in docx_kt]
    assert positions == sorted(positions), \
        "docProps/core.xml elements are out of sequence in DocxWriter.kt"


def test_the_fixed_relationship_ids_match(docx_kt):
    """rId1-5 are the five fixed parts and images start at rId6. A different allocation on the
    phone produces two relationships with one id, and Word drops both pictures in silence."""
    assert str(report_docx._RID_FIRST_IMAGE) in docx_kt
    for part in ("styles.xml", "numbering.xml", "settings.xml", "header1.xml", "footer1.xml"):
        assert part in docx_kt


def test_the_unit_conversions_match(docx_kt):
    """A twip or an EMU that differs makes every margin and every picture the wrong size."""
    assert "1440" in docx_kt and "25.4" in docx_kt, "the twips-per-mm conversion must match"
    assert "36000" in docx_kt, "the EMU-per-mm conversion must match"


def test_the_page_sizes_match(docx_kt, model_kt):
    from app.services.report_model import PageSize

    for size in PageSize:
        w, h = size.size_mm
        combined = docx_kt + model_kt
        assert str(w) in combined and str(h) in combined, f"{size.value} geometry differs"


def test_both_writers_forbid_two_adjacent_tables(docx_kt):
    """Word merges them silently. Both writers assert rather than emit."""
    assert "adjacent" in docx_kt.lower()
    assert "validateBody" in docx_kt


def test_both_writers_emit_a_toc_field_and_ask_word_to_update_it(docx_kt):
    assert "TOC \\\\o" in docx_kt or 'TOC \\o' in docx_kt
    assert 'w:updateFields w:val="true"' in docx_kt


def test_both_writers_suppress_running_furniture_on_the_cover(docx_kt):
    """Without titlePg the header prints over the ministry line and the cover is numbered 1."""
    assert "<w:titlePg/>" in docx_kt


# --------------------------------------------------------------------------------------
# The layout rules the two PDF renderers share
# --------------------------------------------------------------------------------------


def test_the_image_grid_legibility_floor_matches(pdf_kt):
    """Below about 45 mm a photograph stops being evidence. Both renderers drop a column
    rather than shrink past it, so the same grid has the same shape on both surfaces.

    Asserted on the DISTANCE and not on the digits: ``"45" in pdf_kt`` was true of any file
    containing the year 2045 or a 45-degree rotation, and would have gone on passing after the
    floor was changed to 40 on either side.
    """
    import inspect

    from app.services import report_pdf

    python = _mm_distances(inspect.getsource(report_pdf.PdfRenderer._block_image_grid),
                           kotlin=False)
    kotlin = _mm_distances(_kotlin_function(pdf_kt, "blockImageGrid"), kotlin=True)
    assert 45.0 in python, "report_pdf._block_image_grid no longer floors a column at 45 mm"
    assert 45.0 in kotlin, "PdfWriter.blockImageGrid no longer floors a column at 45 mm"


def test_the_cover_hero_minimum_matches(pdf_kt):
    """Both drop the cover photograph below 18 mm rather than print a smudge."""
    import inspect

    from app.services import report_pdf

    python = _mm_distances(inspect.getsource(report_pdf.PdfRenderer._block_cover), kotlin=False)
    kotlin = _mm_distances(_kotlin_function(pdf_kt, "blockCover"), kotlin=True)
    assert 18.0 in python, "report_pdf._block_cover no longer floors the hero photograph at 18 mm"
    assert 18.0 in kotlin, "PdfWriter.blockCover no longer floors the hero photograph at 18 mm"


def test_both_pdf_renderers_measure_before_they_draw(pdf_kt):
    """The two-pass structure is what makes the table of contents' page numbers right."""
    lowered = pdf_kt.lower()
    assert "pass" in lowered and ("measur" in lowered or "drawing" in lowered)


def test_both_pdf_renderers_lock_a_fixed_height_box(pdf_kt):
    """Without it a page breaks between two lines of one table cell, leaving the row's
    background on one page and its words on the next."""
    assert "lock" in pdf_kt.lower()


def test_the_running_head_clearance_is_applied_in_both_passes(pdf_kt):
    """The Python had this guarded by the drawing flag, so its measuring pass believed every
    page after the first was 6 mm taller and every TOC page number could come out one too low.
    The Kotlin port found it. Neither may reintroduce it."""
    assert "6" in pdf_kt
    # The bug shape: applying the clearance only while drawing.
    assert "if (drawing) y = top - 6" not in pdf_kt.replace(" ", "")


#: What "the cursor" means, on each side, for :func:`_cursor_moves_inside_a_drawing_guard`.
#:
#: It is deliberately WIDER than ``y``. The rule the repository has now learned three times is
#: about the two passes seeing the same geometry, and ``y`` is only the most obvious way to break
#: it: incrementing the page counter, entering or leaving the fixed-height lock, or CALLING
#: something that turns a page all move the layout on exactly as far, and each of them behind a
#: drawing guard produces the same silent contents-page error. Naming only ``y`` would have made
#: this test pass over the fourth instance while congratulating itself on the third.
_CURSOR_PY = (
    r"(?<![A-Za-z0-9_.])self\.(?:y\s*(?:=[^=]|[-+]=)|_page\s*(?:=[^=]|[-+]=)"
    r"|_locked_depth\s*(?:=[^=]|[-+]=)|_new_page\s*\(|_ensure\s*\(|_cut_row\s*\()"
)
_CURSOR_KT = (
    r"(?<![A-Za-z0-9_.])(?:y\s*(?:=[^=]|[-+]=)|pageNo\s*(?:=[^=]|[-+]=)"
    r"|lockedDepth\s*(?:=[^=]|[-+]=)|newPage\s*\(|ensure\s*\(|cutRow\s*\()"
)


def _cursor_moves_inside_a_drawing_guard(source: str, *, kotlin: bool) -> list[tuple[int, str]]:
    """Every line that changes the cursor from INSIDE a block guarded by the drawing flag.

    THE RULE, stated in both renderers and broken three times: the drawing flag may guard a DRAW
    CALL and must never guard a change to the cursor. A guarded cursor move makes the measuring
    pass and the drawing pass disagree about how tall the document is, and the only symptom is a
    contents page whose numbers are one or two too low — which nothing on screen contradicts.

    NEGATED GUARDS COUNT TOO, and used to be skipped here. ``if not self._drawing:`` around a
    cursor move is the identical defect with the sign reversed — the measuring pass then believes
    the document is TALLER than the drawing pass will make it and the contents numbers come out
    one too high instead of one too low. The exemption existed because the measuring-only index
    build (``if not self._drawing: self._heading_pages[...] = ...``) sits under such a guard, but
    that line moves nothing, so :data:`_CURSOR_PY` passes over it on its own and the exemption
    was buying nothing but a blind spot.

    Read as text, brace by brace, for the same reason the rest of this file reads Kotlin as text:
    the failure is somebody moving one line inside an ``if``, and it happens in the place this
    finds it. The Python side is read with :func:`inspect.getsource`, so it is the code that
    actually loaded rather than a file on disk that may not be the one imported.
    """
    import re

    def code(line: str) -> str:
        marker = "//" if kotlin else "#"
        cut = line.find(marker)
        return line[:cut] if cut >= 0 else line

    cursor = re.compile(_CURSOR_KT if kotlin else _CURSOR_PY)
    guard = re.compile(r"\bif\s*\(([^)]*\bdrawing\b[^)]*)\)") if kotlin else \
        re.compile(r"\bif\s+([^:]*\b_drawing\b[^:]*):")

    lines = source.splitlines()
    found: list[tuple[int, str]] = []
    for i, raw in enumerate(lines):
        stripped = code(raw)
        match = guard.search(stripped)
        if not match:
            continue
        if kotlin:
            rest = stripped[match.end():]
            if "{" not in rest:
                # A brace-less guard — `if (drawing) startPage()` — governs its own line only.
                # Scanning forward for a closing brace it never opens runs away over the whole
                # file and reports every cursor move in it, which is how the first version of
                # this test failed on four honest lines.
                if cursor.search(rest):
                    found.append((i + 1, raw.strip()))
                continue
            depth = 0
            started = False
            for j in range(i, len(lines)):
                segment = code(lines[j])[match.end():] if j == i else code(lines[j])
                for character in segment:
                    if character == "{":
                        depth += 1
                        started = True
                    elif character == "}":
                        depth -= 1
                if (j > i or started) and cursor.search(code(lines[j])):
                    found.append((j + 1, lines[j].strip()))
                if started and depth == 0:
                    break
        else:
            indent = len(stripped) - len(stripped.lstrip())
            for j in range(i + 1, len(lines)):
                body = lines[j]
                if body.strip() and (len(body) - len(body.lstrip())) <= indent:
                    break
                if cursor.search(code(body)):
                    found.append((j + 1, body.strip()))
    return found


def test_the_kotlin_measuring_pass_moves_the_cursor_outside_every_drawing_guard(pdf_kt):
    """THE LEVEL-1 HEADING RULE, AND THE THIRD TIME THIS CLASS OF BUG HAS SHIPPED.

    ``PdfWriter.blockHeading`` moved ``y`` inside ``if (level == 1 && drawing)``, so the measuring
    pass under-measured every level-1 heading by the 1.2 mm the rule costs. A report with a
    hundred and fifty of them drew long enough to break pages the measuring pass had not, and the
    handset's contents page then printed numbers for a layout that was never drawn.

    ``_new_page`` already carried this lesson in as many words and the server's ``_block_heading``
    carried it a second time, on this same rule. Prose did not stop it, so the SHAPE is asserted
    here — and asserted on the whole file, not on the one site, because the next instance will be
    somewhere else.
    """
    offenders = _cursor_moves_inside_a_drawing_guard(_kotlin("PdfWriter.kt"), kotlin=True)
    assert not offenders, (
        "PdfWriter.kt moves the layout cursor inside a `drawing` guard at "
        + "; ".join(f"line {n}: {text}" for n, text in offenders)
        + " — the drawing flag may guard a DRAW CALL and must never guard a change to y"
    )


def test_the_python_measuring_pass_moves_the_cursor_outside_every_drawing_guard():
    """The same assertion on the server, read off the module that actually imported.

    The server is where this shipped the first two times (the running-head clearance in
    ``_new_page`` and the level-1 rule in ``_block_heading``), and a one-sided pin would let the
    Python drift back while the Kotlin stayed honest.
    """
    import inspect

    from app.services import report_pdf as module

    offenders = _cursor_moves_inside_a_drawing_guard(inspect.getsource(module), kotlin=False)
    assert not offenders, (
        "report_pdf.py moves the layout cursor inside a `self._drawing` guard at "
        + "; ".join(f"line {n}: {text}" for n, text in offenders)
        + " — the drawing flag may guard a DRAW CALL and must never guard a change to self.y"
    )


def test_both_pdf_renderers_split_a_row_taller_than_the_page(pdf_kt):
    """A cell longer than the text column was drawn at a negative y — off the paper — because the
    lock that keeps a row unbroken suppresses every page break, including the one an over-tall row
    needs. Both renderers cut the row and continue it overleaf, which is what the .docx gets for
    free from Word on a ``<w:tr>`` with no ``<w:cantSplit/>``. A renderer that lost the cut would
    silently eat paragraphs again."""
    from app.services import report_pdf

    assert hasattr(report_pdf.PdfRenderer, "_cut_row"), \
        "report_pdf.PdfRenderer no longer cuts an over-tall row"
    assert "fun cutRow(" in pdf_kt, "PdfWriter.kt no longer cuts an over-tall row"
    # And neither may cut inside a region the caller has already reserved — that is the cover.
    assert "lockedDepth > 0" in pdf_kt, \
        "PdfWriter.cutRow must refuse to cut inside a locked region, as the cover depends on"


def test_both_pdf_renderers_reserve_a_heading_s_own_spacing(pdf_kt):
    """keepNext reserved a flat 6 mm for a heading that spends 10.1 mm on its own spacing, so a
    heading could pass its fit test and then walk past the bottom margin. Both renderers now bind
    the lead and the trail once and reserve them plus one following line; a renderer that went
    back to a flat figure would orphan headings again, and the two would paginate differently."""
    import inspect

    from app.services import report_pdf

    heading = inspect.getsource(report_pdf.PdfRenderer._block_heading)
    for token in ("lead", "trail"):
        assert f"{token} +" in heading or f"+ {token}" in heading, \
            f"report_pdf._block_heading no longer reserves its bound `{token}` spacing"
    assert "self.y -= lead" in heading, "the reserved lead must be the lead that is spent"
    assert "val lead = " in pdf_kt and "val trail = " in pdf_kt, \
        "PdfWriter.blockHeading no longer binds its own spacing"
    assert "y -= lead" in pdf_kt, "the reserved lead must be the lead that is spent"


def test_both_pdf_renderers_keep_a_figure_title_with_its_picture(pdf_kt):
    """Both files carried a comment saying a figure title "must not be separated from its picture
    by a page break" and neither opened the locked region that would have prevented it: 32 of 80
    chart titles landed on the page before their chart. A figure title is not in the contents, so
    a stranded one sits over somebody else's picture with nothing to say it does not belong."""
    import inspect

    from app.services import report_pdf

    figure = inspect.getsource(report_pdf.PdfRenderer._block_figure)
    assert "_image_box" in figure, \
        "report_pdf._block_figure no longer measures the picture before reserving space for it"
    assert "with self._locked():" in figure, \
        "report_pdf._block_figure no longer draws the title and the picture as one unit"
    assert "imageBox(" in pdf_kt, \
        "PdfWriter.blockFigure no longer measures the picture before reserving space for it"


def test_both_pdf_renderers_wrap_every_string_they_draw(pdf_kt):
    """The contents entry and the running furniture were the three text paths that never wrapped,
    and all three ran off the sheet: a real section title 164.2 pt past the trim edge, the running
    head 265 pt off the left edge of the paper. Both renderers now put all three through the same
    word wrap, so the two files of one report break their lines in the same places."""
    import inspect

    from app.services import report_pdf

    toc_py = inspect.getsource(report_pdf.PdfRenderer._block_toc)
    assert "self._wrap(runs_of(label" in toc_py, \
        "report_pdf._block_toc draws a contents entry it has not wrapped"
    furniture_py = inspect.getsource(report_pdf.PdfRenderer._draw_furniture)
    for field in ("header_text", "footer_text"):
        assert f"self._wrap(runs_of(meta.{field})" in furniture_py, \
            f"report_pdf._draw_furniture draws an unwrapped {field}"
    # The dot leader and the page number are the two strings that legitimately stay bare: the
    # leader is generated to fit the gap it is drawn in, and the number is three characters
    # right-aligned on a line the wrap has already sized around it.
    toc = pdf_kt[pdf_kt.index("private fun blockToc"):]
    toc = toc[:toc.index("private fun blockHeading")]
    assert "wrap(runsOf(label" in toc, "PdfWriter.blockToc draws a contents entry it has not wrapped"
    furniture = pdf_kt[pdf_kt.index("private fun drawFurniture"):]
    furniture = furniture[:furniture.index("\n    // -- drawing helpers")]
    assert "wrap(runsOf(meta.headerText)" in furniture, "PdfWriter draws an unwrapped running head"
    assert "wrap(runsOf(meta.footerText)" in furniture, "PdfWriter draws an unwrapped running foot"


def test_neither_pdf_renderer_sizes_the_contents_column_from_the_page_number(pdf_kt):
    """The contents entry's wrap column must not depend on WHICH PASS is running.

    Both renderers lay the contents block out before the headings that fill their page-number
    index, so the number is absent while measuring and present while drawing. Sizing the wrap
    column from it made the two passes see different geometry — measured on the server, 453.5 pt
    while measuring against 431.4 pt while drawing, which for a 60-section report put the
    contents on five pages when measured and six when drawn and printed a number one page short
    beside all sixty entries. The room for the number is reserved from a constant instead, and
    the label still governs only whether a number and a leader are DRAWN.
    """
    import inspect

    from app.services import report_pdf

    def avail_statement(source: str, keyword: str) -> str:
        return next(row for row in source.splitlines() if row.strip().startswith(keyword))

    toc_py = inspect.getsource(report_pdf.PdfRenderer._block_toc)
    py_avail = avail_statement(toc_py, "avail = ")
    assert "page_label" not in py_avail and "page_w" not in py_avail,         f"report_pdf._block_toc sizes the contents column from the page number: {py_avail.strip()}"
    toc_kt = pdf_kt[pdf_kt.index("private fun blockToc"):]
    toc_kt = toc_kt[:toc_kt.index("private fun blockHeading")]
    kt_avail = avail_statement(toc_kt, "val avail = ")
    assert "pageLabel" not in kt_avail,         f"PdfWriter.blockToc sizes the contents column from the page number: {kt_avail.strip()}"


# --------------------------------------------------------------------------------------
# The theme
# --------------------------------------------------------------------------------------


def test_the_default_theme_colours_match(model_kt):
    """A colour that differs makes the phone's report and the server's visibly different
    documents for the same workshop."""
    theme = ReportTheme()
    for value in (theme.accent, theme.accent_soft, theme.table_header_fill, theme.zebra_fill,
                  theme.rule, theme.muted):
        assert value in model_kt, f"theme colour {value} differs in ReportModel.kt"


def test_the_default_body_size_matches(model_kt):
    assert str(ReportTheme().base_size_pt) in model_kt


def test_the_complex_script_font_matches(model_kt, docx_kt):
    """w:cs is the only lever a .docx has over shaping; a different face on the phone changes
    which craft names render and which become boxes."""
    assert ReportTheme().complex_font in (model_kt + docx_kt)


def test_the_reportlab_shaping_limitation_is_recorded():
    """The server PDF cannot shape Indic scripts and the on-device one can. If that stops being
    written down, somebody will eventually treat the two PDFs as interchangeable."""
    assert "shap" in report_pdf.__doc__.lower()


def test_both_docx_writers_rotate_the_pixels_not_only_the_frame(docx_kt):
    """Sizing the frame for a rotated photo but drawing the bytes unrotated stretched every
    phone-portrait photograph across a landscape box. Both writers must emit the DrawingML
    rotation, and both must swap a:ext against wp:extent for a quarter turn."""
    assert "60000" in docx_kt, "the DrawingML angle unit is missing from DocxWriter.kt"
    assert "rot=" in docx_kt, "DocxWriter.kt emits no rotation attribute"


def test_a_rotated_photo_gets_a_landscape_frame_and_a_portrait_extent():
    """The pair is the classic OOXML rotation bug: get it the wrong way round and Word renders
    the picture rotated but cropped to the wrong box."""
    import re

    from app.services.report_docx import render_docx
    from app.services.report_model import DocumentBuilder, ImageBlock, ImageRef, ReportMeta

    builder = DocumentBuilder(meta=ReportMeta(title="rot", generated_at="2026-01-01T00:00:00Z"))
    builder.add(ImageBlock(image=ImageRef("p", 300, 400, rotation_deg=90,
                                          mime_type="image/png"), width_pct=50.0))
    data, _dropped = render_docx(builder.build(), lambda _ref: _png(300, 400))
    doc = zipfile.ZipFile(BytesIO(data)).read("word/document.xml").decode()

    match = re.search(
        r'<wp:extent cx="(\d+)" cy="(\d+)"/>.*?<a:xfrm rot="(\d+)"><a:off[^/]*/>'
        r'<a:ext cx="(\d+)" cy="(\d+)"',
        doc, re.DOTALL,
    )
    assert match, "no rotated drawing was emitted"
    frame_cx, frame_cy, rot, ext_cx, ext_cy = (int(g) for g in match.groups())
    assert rot == 90 * 60000
    assert frame_cx > frame_cy, "a quarter-turned portrait occupies a landscape box"
    assert ext_cx == frame_cy and ext_cy == frame_cx, "a:ext is the UNROTATED extent"


# --------------------------------------------------------------------------------------
# The rich-text document
# --------------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def rich_kt() -> str:
    return _kotlin("RichText.kt")


def test_both_rich_text_models_declare_the_same_block_kinds(rich_kt):
    """A kind the phone does not know is a paragraph the phone DELETES.

    This is worse than the report-block case above and worth stating plainly. `coerceKind`
    degrades an unrecognised kind to PARAGRAPH, and the parser then reads only `spans` — so for a
    kind whose content lives anywhere else (TABLE keeps it in `rows`), a phone one release behind
    opens the field, finds nothing in it, and writes the field back EMPTY. The designer loses the
    table by the ordinary act of opening the stage, with no error anywhere.

    Reading the source rather than running the Kotlin is the same blunt instrument the rest of
    this file uses, and it catches the same thing: somebody adding a kind on one side only.
    """
    from app.services.rich_text import BlockKind

    for kind in BlockKind:
        assert re.search(rf"^\s*{kind.value}[,;]", rich_kt, re.MULTILINE), (
            f"BlockKind.{kind.value} is in rich_text.py but not in RichText.kt — a phone that "
            f"does not know it will silently discard the content of every such block"
        )


def test_both_rich_text_models_declare_the_same_marks(rich_kt):
    from app.services.rich_text import Mark

    for mark in Mark:
        assert re.search(rf"^\s*{mark.value}[,;]", rich_kt, re.MULTILINE), (
            f"Mark.{mark.value} is in rich_text.py but not in RichText.kt"
        )


def test_both_pdf_renderers_raise_and_lower_text_by_the_same_amounts(pdf_kt):
    """A superscript is not a property in a PDF — it is a smaller glyph drawn off the baseline.

    So unlike ``<w:vertAlign/>`` in the .docx, which Word resolves, the two PDF renderers each
    decide how far and how small, from three numbers written twice. A number that differs makes
    "m²" sit visibly higher in the report generated on the phone than in the one downloaded from
    the office — the same document, two typographies, and nothing to explain it.
    """
    from app.services import report_pdf

    for name in ("VERTICAL_SCALE", "SUPERSCRIPT_RISE", "SUBSCRIPT_DROP"):
        value = getattr(report_pdf, name)
        assert re.search(rf"{name}\s*(:\s*\w+\s*)?=\s*{value}f?\b", pdf_kt), (
            f"{name} is {value} in report_pdf.py; PdfWriter.kt disagrees"
        )


def test_both_renderers_fill_the_same_highlight(model_kt, docx_kt, pdf_kt):
    """A highlight is the ONE mark whose entire content is its colour.

    The .docx cannot choose it — ``w:highlight`` names one of sixteen values and Word draws the
    pixels — so the two PDF renderers, which fill a rectangle themselves, have to fill exactly the
    hex Word uses or the same sentence is a different colour in the two files of one report.
    """
    from app.services.report_model import HIGHLIGHT_FILL

    assert HIGHLIGHT_FILL in model_kt, "HIGHLIGHT_FILL differs in ReportModel.kt"
    assert 'w:highlight w:val="yellow"' in docx_kt, "DocxWriter.kt names a different highlight"
    assert "HIGHLIGHT_FILL" in pdf_kt, "PdfWriter.kt fills something other than the shared constant"


def test_both_docx_writers_emit_one_vertical_alignment_element(docx_kt):
    """``CT_VerticalAlignRun`` takes ONE value, so a run that claimed both marks would be
    schema-invalid and Word would refuse the file. Both writers read a single-valued Run instead
    of choosing, and both spell the values the way the schema does."""
    assert "w:vertAlign" in docx_kt, "DocxWriter.kt emits no vertical alignment at all"
    assert "superscript" in docx_kt and "subscript" in docx_kt


def test_both_rich_text_models_agree_on_their_bounds(rich_kt):
    """A bound that differs means one side silently truncates what the other accepted."""
    from app.services import rich_text

    for name in ("MAX_DOCUMENT_CHARS", "MAX_BLOCKS", "MAX_HEADING_LEVEL", "MAX_LIST_DEPTH",
                 "MAX_TABLE_ROWS", "MAX_TABLE_COLUMNS"):
        value = getattr(rich_text, name)
        # Kotlin writes 200_000 for 200000; compare on the digits with separators removed.
        assert re.search(rf"{name}\s*=\s*{value:_}".replace("_", "_?"), rich_kt) or \
               re.search(rf"{name}\s*=\s*{value}", rich_kt), (
            f"{name} is {value} in rich_text.py; RichText.kt disagrees"
        )


# --------------------------------------------------------------------------------------
# SHAPE MIRRORS — the assertions that fail whichever side moves
# --------------------------------------------------------------------------------------
#
# Everything above this line grew one assertion per defect, and almost all of it reads ONE side:
# a substring searched for in the Kotlin, a constant looked up in the Python. That is enough to
# stop the exact line somebody already broke and nothing else, and the class it fails to stop is
# the one this repository keeps paying for — A FIX LANDS ON THE SERVER AND NEVER REACHES THE
# HANDSET. The proof is in the history: the measure-versus-draw bug was fixed in `report_pdf.py`
# in a commit that touched no Kotlin, so the phone went on printing a contents page for a layout
# it never drew, and the defect had already shipped twice on the server before that.
#
# The five tests below are therefore not about five defects. Each reads EVERY SURFACE THAT COULD
# DRIFT, derives what it compares from the code rather than from a literal written into the test,
# and fails whichever side moves. They are meant to be broken by work that has not been done yet,
# and the last one that was — the web preview printing a page label the four file renderers had
# stopped printing — now agrees; the mirror below is what keeps it agreeing, and it reads the
# preview's own source from this side of the repository for the same reason the Kotlin is read from
# here: a pin that lives only next to the code it guards goes out in the edit that breaks it.


def test_every_layout_distance_is_the_same_millimetre_on_both_surfaces(pdf_kt):
    """Every ``* MM`` in the paired layout methods, compared server against handset.

    This is the general form of the three defects the tests above pin one at a time — the 6 mm
    running-head clearance, the 1.2 mm heading rule, the 45 mm photograph floor. It pins the
    whole renderer's geometry instead, so the NEXT distance that changes on one side fails here
    without anybody having to remember to write a test for it.

    HOW MUCH IT ACTUALLY COVERS, measured rather than estimated: :data:`_LAYOUT_PAIRS` names 20
    pairs, of which 18 contribute anything — ``_image_box``/``imageBox`` and
    ``_draw_image``/``drawImage`` contain no ``MM`` token at all and pin NOTHING, so nobody
    should read the tuple as twenty covered methods. Across the 18 the extractor yields 73
    per-method values drawn from 29 distinct millimetre figures. Those numbers are what the
    helper returns today, not a target: a pair that starts using ``MM`` joins the count on its
    own, which is the property that makes this test worth more than the three above it.

    A SET PER METHOD, so an exchange WITHIN one method is invisible — see :func:`_mm_distances`
    for why that trade is made and what it costs.

    A pair whose numbers differ is reported with both sets, because the interesting information
    is never "they differ" but which side is carrying the number the other has not been told.
    """
    import inspect

    from app.services.report_pdf import PdfRenderer

    problems: list[str] = []
    for python_name, kotlin_name in _LAYOUT_PAIRS:
        python = _mm_distances(inspect.getsource(getattr(PdfRenderer, python_name)), kotlin=False)
        kotlin = _mm_distances(_kotlin_function(pdf_kt, kotlin_name), kotlin=True)
        if python != kotlin:
            problems.append(
                f"{python_name} / {kotlin_name}: only in report_pdf.py "
                f"{sorted(python - kotlin)}, only in PdfWriter.kt {sorted(kotlin - python)}"
            )
    assert not problems, (
        "the two PDF renderers disagree about a distance in millimetres — "
        + "; ".join(problems)
    )


def test_all_four_renderers_draw_the_same_set_of_blocks(pdf_kt, docx_kt):
    """The model's block types, against what each of the four renderers actually dispatches.

    ``test_both_models_declare_the_same_block_types`` checks that the phone's MODEL knows every
    block. It does not check that anything RENDERS one, and those are different failures: a
    block type the model carries and a renderer's dispatch does not is a section of the report
    that is simply absent from that one file, with no error and nothing in the other three to
    suggest a reader is missing anything.

    Read off the dispatch tables themselves, so a block added to the model reaches this test
    without the test being edited.
    """
    import inspect

    from app.services import report_docx, report_model, report_pdf

    model = {name for name in dir(report_model) if name.endswith("Block") and name != "Block"}
    surfaces = {
        "report_pdf.PdfRenderer._run_pass": set(re.findall(
            r"isinstance\(block, (\w+)\)",
            inspect.getsource(report_pdf.PdfRenderer._run_pass))),
        "report_docx.DocxWriter._emit_blocks": set(re.findall(
            r"isinstance\(block, (\w+)\)",
            inspect.getsource(report_docx.DocxWriter._emit_blocks))),
        "PdfWriter.runPass": set(re.findall(
            r"is (\w+) ->", _kotlin_function(pdf_kt, "runPass"))),
        "DocxWriter.emitBlocks": set(re.findall(
            r"is (\w+) ->", _kotlin_function(docx_kt, "emitBlocks"))),
    }
    for where, dispatched in surfaces.items():
        assert dispatched == model, (
            f"{where} does not render the same blocks the model declares: missing "
            f"{sorted(model - dispatched)}, unknown to the model {sorted(dispatched - model)}"
        )


def test_all_four_file_renderers_number_a_page_the_same_way(pdf_kt, docx_kt):
    """Page N of M — in both .docx writers, in the server PDF and on the handset PDF.

    THE DIVERGENCE THIS CLOSES WAS REAL AND SHIPPED. Both .docx writers have printed "Page N of M"
    since they were written — Word resolves its own ``NUMPAGES`` field — and both PDF renderers
    printed "Page N", because a PDF cannot ask itself how long it is. So the two files of one
    export numbered their pages differently, and a reader holding both had nothing to tell them
    whether the PDF was the whole document or an extract of it. The measuring pass knows the total
    before a page is drawn, so both PDF renderers now print it.

    Asserted on ALL FOUR FILE RENDERERS, including the Android ``DocxWriter`` that agrees today
    and is therefore the one nobody would notice going quiet. This is exactly the shape that
    produced the class: fix one surface, leave the others, and the files of one report disagree.

    A FIFTH SURFACE PRINTS THIS LABEL and is not asserted here — the web preview, which is not a
    file renderer and cannot be reached by ``inspect`` or by the Kotlin reader. It has its own
    test immediately below, reading the .tsx as text.
    """
    import inspect

    from app.services import report_docx, report_pdf

    docx = inspect.getsource(report_docx.DocxWriter._footer_xml)
    assert 'Run(text="Page ")' in docx and 'Run(text=" of ")' in docx, \
        "report_docx no longer builds the label 'Page N of M'"
    assert '_field("PAGE")' in docx and '_field("NUMPAGES")' in docx, \
        "report_docx no longer resolves the total from NUMPAGES"

    docx_kotlin = _kotlin_function(docx_kt, "footerXml")
    assert 'Run(text = "Page ")' in docx_kotlin and 'Run(text = " of ")' in docx_kotlin, \
        "DocxWriter.kt no longer builds the label 'Page N of M', and its three siblings do"
    assert 'field("PAGE")' in docx_kotlin and 'field("NUMPAGES")' in docx_kotlin, \
        "DocxWriter.kt no longer resolves the total from NUMPAGES, and report_docx does"

    furniture = inspect.getsource(report_pdf.PdfRenderer._draw_furniture)
    assert 'f"Page {self._page} of {self._total_pages}"' in furniture, \
        "report_pdf's running foot no longer prints the total, and the .docx twin still does"
    kotlin = _kotlin_function(pdf_kt, "drawFurniture")
    assert '"Page $pageNo of $totalPages"' in kotlin, \
        "PdfWriter's running foot no longer prints the total, and both its siblings do"

    # And the total is the MEASURED one on both sides. Anything else would be a count taken
    # during the drawing pass, which cannot be known until the last page has already printed it.
    build = inspect.getsource(report_pdf.PdfRenderer.build)
    assert "self._total_pages = self._page" in build, \
        "report_pdf no longer takes the page total from the measuring pass"
    write_to = _kotlin_function(pdf_kt, "writeTo")
    assert "totalPages = pageNo" in write_to, \
        "PdfWriter no longer takes the page total from the measuring pass"


@pytest.mark.skipif(not _FRONTEND.is_dir(),
                    reason="the web client is not present in this checkout")
def test_the_web_preview_numbers_a_page_the_way_the_four_file_renderers_do():
    """The FIFTH surface that prints this label, and the one the fix reached last.

    THE PAGE-LABEL FIX WENT TO FOUR SURFACES OUT OF FIVE, which is the class this section exists
    for, one level up: not a fix that reached the server and missed the handset, but a fix that
    reached every FILE and missed the SCREEN. A designer proofs the report in the browser, reads
    "Page 3" in the running foot, downloads it, hands a ministry officer a document that says
    "Page 3 of 12", and the two disagree in exactly the way the fix itself argues matters — the
    reader holding both has nothing to tell them which is the whole document.

    Read as text, the way the Kotlin is read, and for the same reason: the assertion is about
    somebody editing a label in one place and not the others, which is visible in the source.
    """
    sheet = (_FRONTEND / "ReportSheet.tsx").read_text(encoding="utf-8")
    model = (_FRONTEND / "previewModel.ts").read_text(encoding="utf-8")

    # Read as two halves, because the label is deliberately built from two elements: the "of M"
    # is wrapped so that `@media print` can drop it — the strip that declares M a floor is chrome
    # and does not print, and an unqualified total in a file somebody emails is the whole hazard.
    # A single-substring pin would have read that wrapper as a removal of the total.
    assert "Page {sheet.pageNumber}" in sheet, (
        "the web preview's running foot no longer prints a page ordinal at all, and all four file "
        "renderers do — see `rp-runfoot` in ReportSheet.tsx"
    )
    assert " of {sheets.length}" in sheet, (
        "the web preview's running foot does not print a total, and all four file renderers do — "
        "ReportSheet.tsx renders `Page {sheet.pageNumber}` in `rp-runfoot` with no `of M` beside it"
    )
    assert '"Page N"' not in model, (
        "previewModel.ts still documents `pageNumber` as matching the \"Page N\" the .pdf writer "
        "draws; the .pdf writer draws \"Page N of M\", so the comment describes a label that no "
        "renderer prints any more"
    )


def test_both_pdf_renderers_settle_the_contents_by_the_same_rules(pdf_kt):
    """The measuring loop: how many times it may run, how it stops, the reconciling pass, and
    what each renderer does when the drawing pass disagrees with it anyway.

    THIS IS THE INSTANCE OF THE CLASS THAT WAS STILL OPEN when these mirrors were written.
    ``report_pdf.build`` was taught four things the handset was never told: that a cap of three
    iterations is a bound on TIME and not a convergence argument, that an oscillating document
    should be recognised rather than run out of turns, that on any exit which is not a clean
    convergence the page map was measured against the PREDECESSOR of the contents block about to
    be drawn — so the numbers and the pagination come from two different layouts — and that the
    drawing pass should be checked against the measured total afterwards, because none of the
    other three can guarantee it. ``PdfWriter.writeTo`` still capped at three, still had no
    oscillation memory, still had no reconciling pass and still said nothing when the counts
    disagreed, so the same long report settled differently on the two surfaces, printed two
    different contents pages from it, and reported the fact on one surface only.

    Pinned as SHAPE on both sides rather than as an outcome, because the outcome — a contents
    number one or two too low — is invisible in every artefact except the printed page, and
    whether a given document shows it at all depends on the font the host happened to bind.
    """
    import inspect

    from app.services import report_pdf

    build = inspect.getsource(report_pdf.PdfRenderer.build)
    write_to = _kotlin_function(pdf_kt, "writeTo")

    assert "for _ in range(8):" in build, "report_pdf no longer caps the measuring loop at eight"
    assert "for (iteration in 0 until 8)" in write_to, \
        "PdfWriter no longer caps the measuring loop at eight, and the server does"

    # An oscillation is RECOGNISED on both sides, rather than being left to hit the cap.
    assert "signature in seen" in build, \
        "report_pdf no longer recognises a layout it has already measured"
    assert "seen.add(" in write_to, \
        "PdfWriter no longer recognises a layout it has already measured"

    # The reconciling pass: one more measurement with the contents block left exactly as the loop
    # left it, so the page map describes the layout that is about to be drawn.
    assert build.count("self._run_pass(drawing=False)") == 2, (
        "report_pdf no longer measures once more after the loop with `_toc_source` untouched — "
        "the contents numbers would then belong to a layout that is not the one drawn"
    )
    assert write_to.count("runPass(drawing = false)") == 2, (
        "PdfWriter no longer measures once more after the loop with `tocEntries` untouched — "
        "the contents numbers would then belong to a layout that is not the one drawn"
    )
    # And neither may re-adopt the contents after that pass, which would put the block one step
    # ahead of the map again — the exact shape of the original defect, one iteration later.
    reconcile_py = build[build.rindex("self._run_pass(drawing=False)"):]
    assert "_toc_source" not in reconcile_py, \
        "report_pdf re-adopts the contents after the reconciling pass"
    reconcile_kt = write_to[write_to.rindex("runPass(drawing = false)"):]
    assert "tocEntries" not in reconcile_kt, \
        "PdfWriter re-adopts the contents after the reconciling pass"

    # AND BOTH SAY SO WHEN THE SETTLING FAILED. None of the above can guarantee the drawing pass
    # paginates the way the last measuring pass did — that is a property of every block method,
    # not of this loop — so each renderer compares the two counts at the end and reports the
    # disagreement. The check itself was the last server-only line in this pair: `report_pdf`
    # logged it and `PdfWriter` did nothing, so the failure the log exists to surface stayed
    # invisible on the handset. It is pinned here because it is not reachable from the outputs:
    # a document that triggers it renders perfectly well, it just numbers itself wrongly.
    assert "self._page != self._total_pages" in build, (
        "report_pdf no longer notices that the drawing pass and the measuring pass produced "
        "different page counts"
    )
    assert "pageNo != totalPages" in write_to, (
        "PdfWriter no longer notices that the drawing pass and the measuring pass produced "
        "different page counts, and report_pdf does — the failure is silent on the handset again"
    )
    assert "pageCountDisagreement = pageNo to totalPages" in write_to, (
        "PdfWriter notices the disagreement and keeps it to itself; it must be readable by the "
        "caller, because `renderPdf` does not return it and a phone has no server log"
    )
