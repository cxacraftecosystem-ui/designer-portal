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
    rather than shrink past it, so the same grid has the same shape on both surfaces."""
    assert "45" in pdf_kt


def test_the_cover_hero_minimum_matches(pdf_kt):
    """Both drop the cover photograph below 18 mm rather than print a smudge."""
    assert "18" in pdf_kt


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


def _cursor_moves_inside_a_drawing_guard(source: str, *, kotlin: bool) -> list[tuple[int, str]]:
    """Every line that changes the cursor from INSIDE a block guarded by the drawing flag.

    THE RULE, stated in both renderers and broken three times: the drawing flag may guard a DRAW
    CALL and must never guard a change to ``y``. A guarded cursor move makes the measuring pass
    and the drawing pass disagree about how tall the document is, and the only symptom is a
    contents page whose numbers are one or two too low — which nothing on screen contradicts.

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

    cursor = re.compile(r"(?<![A-Za-z0-9_.])y\s*(=[^=]|[-+]=)") if kotlin else \
        re.compile(r"(?<![A-Za-z0-9_.])self\.y\s*(=[^=]|[-+]=)")
    guard = re.compile(r"\bif\s*\(([^)]*\bdrawing\b[^)]*)\)") if kotlin else \
        re.compile(r"\bif\s+([^:]*\b_drawing\b[^:]*):")

    lines = source.splitlines()
    found: list[tuple[int, str]] = []
    for i, raw in enumerate(lines):
        stripped = code(raw)
        match = guard.search(stripped)
        if not match or match.group(1).strip().startswith(("!", "not ")):
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
