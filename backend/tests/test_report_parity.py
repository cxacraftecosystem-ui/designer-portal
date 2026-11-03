import re
import zipfile
from io import BytesIO
from pathlib import Path

import pytest

from app.services import report_docx
from app.services.report_model import ReportTheme
from tests.test_report_docx import _png

_ANDROID = (
    Path(__file__).resolve().parents[2]
    / "android/app/src/main/java/com/designprototype/workshop/report"
)

_FRONTEND = Path(__file__).resolve().parents[2] / "frontend/components/designworkshop/report"

pytestmark = pytest.mark.skipif(
    not _ANDROID.is_dir(), reason="the Android source tree is not present in this checkout"
)


def _kotlin(name: str) -> str:
    text = (_ANDROID / name).read_text(encoding="utf-8")
    text = text.replace('\\"', '"')
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
    if not kotlin:
        import io
        import textwrap
        import tokenize

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
    import re

    stripped = _strip_comments(source, kotlin=kotlin)
    number = re.compile(r"\d+(?:\.\d+)?")
    found: set[float] = set()
    for match in re.finditer(r"\*\s*MM\b", stripped):
        head = stripped[:match.start()].rstrip()
        if head.endswith(")"):
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



def test_both_models_declare_the_same_block_types(model_kt):
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
    from app.services.report_model import _SCRIPT_RANGES

    for lo, hi, script in _SCRIPT_RANGES:
        assert f"0x{lo:04X}" in model_kt or f"0x{lo:04x}" in model_kt, \
            f"{script.value} lower bound 0x{lo:04X} missing from ReportModel.kt"
        assert f"0x{hi:04X}" in model_kt or f"0x{hi:04x}" in model_kt, \
            f"{script.value} upper bound 0x{hi:04X} missing from ReportModel.kt"


def test_the_table_width_tolerance_matches(model_kt):
    assert "100.0) > 0.5" in model_kt or "100.0) > 0.5f" in model_kt



def test_the_main_document_part_type_matches(docx_kt):
    assert ".document.main+xml" in docx_kt
    assert 'PartName="/word/document.xml" ContentType="' + report_docx.DOCX_MIME not in docx_kt


def test_the_schema_sequence_orderings_match(docx_kt):
    assert "<w:cantSplit/><w:tblHeader/>" in docx_kt, \
        "cantSplit must precede tblHeader in DocxWriter.kt, as it does in report_docx.py"

    tbl_pr = docx_kt[docx_kt.index("<w:tbl><w:tblPr>"):]
    tbl_pr = tbl_pr[:tbl_pr.index("</w:tblPr>")]
    borders_at = min(
        (tbl_pr.index(token) for token in ("<w:tblBorders>", "borderXml") if token in tbl_pr),
        default=-1,
    )
    assert borders_at >= 0, "DocxWriter.kt emits no table borders at all"
    assert borders_at < tbl_pr.index("<w:tblLayout"), \
        "tblBorders must be concatenated before tblLayout in DocxWriter.kt"


def test_the_core_properties_order_matches(docx_kt):
    order = ["<cp:category>", "<dcterms:created", "<dc:creator>", "<cp:lastModifiedBy>",
             "<dcterms:modified", "<dc:subject>", "<dc:title>"]
    positions = [docx_kt.index(tag) for tag in order if tag in docx_kt]
    assert positions == sorted(positions), \
        "docProps/core.xml elements are out of sequence in DocxWriter.kt"


def test_the_fixed_relationship_ids_match(docx_kt):
    assert str(report_docx._RID_FIRST_IMAGE) in docx_kt
    for part in ("styles.xml", "numbering.xml", "settings.xml", "header1.xml", "footer1.xml"):
        assert part in docx_kt


def test_the_unit_conversions_match(docx_kt):
    assert "1440" in docx_kt and "25.4" in docx_kt, "the twips-per-mm conversion must match"
    assert "36000" in docx_kt, "the EMU-per-mm conversion must match"


def test_the_page_sizes_match(docx_kt, model_kt):
    from app.services.report_model import PageSize

    for size in PageSize:
        w, h = size.size_mm
        combined = docx_kt + model_kt
        assert str(w) in combined and str(h) in combined, f"{size.value} geometry differs"


def test_both_writers_forbid_two_adjacent_tables(docx_kt):
    assert "adjacent" in docx_kt.lower()
    assert "validateBody" in docx_kt


def test_both_writers_emit_a_toc_field_and_ask_word_to_update_it(docx_kt):
    assert "TOC \\\\o" in docx_kt or 'TOC \\o' in docx_kt
    assert 'w:updateFields w:val="true"' in docx_kt


def test_both_writers_suppress_running_furniture_on_the_cover(docx_kt):
    assert "<w:titlePg/>" in docx_kt



def test_the_image_grid_legibility_floor_matches(pdf_kt):
    import inspect

    from app.services import report_pdf

    python = _mm_distances(inspect.getsource(report_pdf.PdfRenderer._block_image_grid),
                           kotlin=False)
    kotlin = _mm_distances(_kotlin_function(pdf_kt, "blockImageGrid"), kotlin=True)
    assert 45.0 in python, "report_pdf._block_image_grid no longer floors a column at 45 mm"
    assert 45.0 in kotlin, "PdfWriter.blockImageGrid no longer floors a column at 45 mm"


def test_the_cover_hero_minimum_matches(pdf_kt):
    import inspect

    from app.services import report_pdf

    python = _mm_distances(inspect.getsource(report_pdf.PdfRenderer._block_cover), kotlin=False)
    kotlin = _mm_distances(_kotlin_function(pdf_kt, "blockCover"), kotlin=True)
    assert 18.0 in python, "report_pdf._block_cover no longer floors the hero photograph at 18 mm"
    assert 18.0 in kotlin, "PdfWriter.blockCover no longer floors the hero photograph at 18 mm"


def test_both_pdf_renderers_measure_before_they_draw(pdf_kt):
    lowered = pdf_kt.lower()
    assert "pass" in lowered and ("measur" in lowered or "drawing" in lowered)


def test_both_pdf_renderers_lock_a_fixed_height_box(pdf_kt):
    assert "lock" in pdf_kt.lower()


def test_the_running_head_clearance_is_applied_in_both_passes(pdf_kt):
    assert "6" in pdf_kt
    assert "if (drawing) y = top - 6" not in pdf_kt.replace(" ", "")


_CURSOR_PY = (
    r"(?<![A-Za-z0-9_.])self\.(?:y\s*(?:=[^=]|[-+]=)|_page\s*(?:=[^=]|[-+]=)"
    r"|_locked_depth\s*(?:=[^=]|[-+]=)|_new_page\s*\(|_ensure\s*\(|_cut_row\s*\()"
)
_CURSOR_KT = (
    r"(?<![A-Za-z0-9_.])(?:y\s*(?:=[^=]|[-+]=)|pageNo\s*(?:=[^=]|[-+]=)"
    r"|lockedDepth\s*(?:=[^=]|[-+]=)|newPage\s*\(|ensure\s*\(|cutRow\s*\()"
)


def _cursor_moves_inside_a_drawing_guard(source: str, *, kotlin: bool) -> list[tuple[int, str]]:
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
    offenders = _cursor_moves_inside_a_drawing_guard(_kotlin("PdfWriter.kt"), kotlin=True)
    assert not offenders, (
        "PdfWriter.kt moves the layout cursor inside a `drawing` guard at "
        + "; ".join(f"line {n}: {text}" for n, text in offenders)
        + " — the drawing flag may guard a DRAW CALL and must never guard a change to y"
    )


def test_the_python_measuring_pass_moves_the_cursor_outside_every_drawing_guard():
    import inspect

    from app.services import report_pdf as module

    offenders = _cursor_moves_inside_a_drawing_guard(inspect.getsource(module), kotlin=False)
    assert not offenders, (
        "report_pdf.py moves the layout cursor inside a `self._drawing` guard at "
        + "; ".join(f"line {n}: {text}" for n, text in offenders)
        + " — the drawing flag may guard a DRAW CALL and must never guard a change to self.y"
    )


def test_both_pdf_renderers_split_a_row_taller_than_the_page(pdf_kt):
    from app.services import report_pdf

    assert hasattr(report_pdf.PdfRenderer, "_cut_row"), \
        "report_pdf.PdfRenderer no longer cuts an over-tall row"
    assert "fun cutRow(" in pdf_kt, "PdfWriter.kt no longer cuts an over-tall row"
    assert "lockedDepth > 0" in pdf_kt, \
        "PdfWriter.cutRow must refuse to cut inside a locked region, as the cover depends on"


def test_both_pdf_renderers_reserve_a_heading_s_own_spacing(pdf_kt):
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
    import inspect

    from app.services import report_pdf

    toc_py = inspect.getsource(report_pdf.PdfRenderer._block_toc)
    assert "self._wrap(runs_of(label" in toc_py, \
        "report_pdf._block_toc draws a contents entry it has not wrapped"
    furniture_py = inspect.getsource(report_pdf.PdfRenderer._draw_furniture)
    for field in ("header_text", "footer_text"):
        assert f"self._wrap(runs_of(meta.{field})" in furniture_py, \
            f"report_pdf._draw_furniture draws an unwrapped {field}"
    toc = pdf_kt[pdf_kt.index("private fun blockToc"):]
    toc = toc[:toc.index("private fun blockHeading")]
    assert "wrap(runsOf(label" in toc, "PdfWriter.blockToc draws a contents entry it has not wrapped"
    furniture = _kotlin_function(pdf_kt, "drawFurniture")
    assert "wrap(runsOf(meta.headerText)" in furniture, "PdfWriter draws an unwrapped running head"
    assert "wrap(runsOf(meta.footerText)" in furniture, "PdfWriter draws an unwrapped running foot"


def test_neither_pdf_renderer_sizes_the_contents_column_from_the_page_number(pdf_kt):
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



def test_the_default_theme_colours_match(model_kt):
    theme = ReportTheme()
    for value in (theme.accent, theme.accent_soft, theme.table_header_fill, theme.zebra_fill,
                  theme.rule, theme.muted):
        assert value in model_kt, f"theme colour {value} differs in ReportModel.kt"


def test_the_default_body_size_matches(model_kt):
    assert str(ReportTheme().base_size_pt) in model_kt


def test_the_complex_script_font_matches(model_kt, docx_kt):
    assert ReportTheme().complex_font in (model_kt + docx_kt)


def test_both_docx_writers_rotate_the_pixels_not_only_the_frame(docx_kt):
    assert "60000" in docx_kt, "the DrawingML angle unit is missing from DocxWriter.kt"
    assert "rot=" in docx_kt, "DocxWriter.kt emits no rotation attribute"


def test_a_rotated_photo_gets_a_landscape_frame_and_a_portrait_extent():
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



@pytest.fixture(scope="module")
def rich_kt() -> str:
    return _kotlin("RichText.kt")


def test_both_rich_text_models_declare_the_same_block_kinds(rich_kt):
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
    from app.services import report_pdf

    for name in ("VERTICAL_SCALE", "SUPERSCRIPT_RISE", "SUBSCRIPT_DROP"):
        value = getattr(report_pdf, name)
        assert re.search(rf"{name}\s*(:\s*\w+\s*)?=\s*{value}f?\b", pdf_kt), (
            f"{name} is {value} in report_pdf.py; PdfWriter.kt disagrees"
        )


def test_both_renderers_fill_the_same_highlight(model_kt, docx_kt, pdf_kt):
    from app.services.report_model import HIGHLIGHT_FILL

    assert HIGHLIGHT_FILL in model_kt, "HIGHLIGHT_FILL differs in ReportModel.kt"
    assert 'w:highlight w:val="yellow"' in docx_kt, "DocxWriter.kt names a different highlight"
    assert "HIGHLIGHT_FILL" in pdf_kt, "PdfWriter.kt fills something other than the shared constant"


def test_both_docx_writers_emit_one_vertical_alignment_element(docx_kt):
    assert "w:vertAlign" in docx_kt, "DocxWriter.kt emits no vertical alignment at all"
    assert "superscript" in docx_kt and "subscript" in docx_kt


def test_both_rich_text_models_agree_on_their_bounds(rich_kt):
    from app.services import rich_text

    for name in ("MAX_DOCUMENT_CHARS", "MAX_BLOCKS", "MAX_HEADING_LEVEL", "MAX_LIST_DEPTH",
                 "MAX_TABLE_ROWS", "MAX_TABLE_COLUMNS"):
        value = getattr(rich_text, name)
        assert re.search(rf"{name}\s*=\s*{value:_}".replace("_", "_?"), rich_kt) or \
               re.search(rf"{name}\s*=\s*{value}", rich_kt), (
            f"{name} is {value} in rich_text.py; RichText.kt disagrees"
        )



def test_every_layout_distance_is_the_same_millimetre_on_both_surfaces(pdf_kt):
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

    build = inspect.getsource(report_pdf.PdfRenderer.build)
    assert "self._total_pages = self._page" in build, \
        "report_pdf no longer takes the page total from the measuring pass"
    write_to = _kotlin_function(pdf_kt, "writeTo")
    assert "totalPages = pageNo" in write_to, \
        "PdfWriter no longer takes the page total from the measuring pass"


@pytest.mark.skipif(not _FRONTEND.is_dir(),
                    reason="the web client is not present in this checkout")
def test_the_web_preview_numbers_a_page_the_way_the_four_file_renderers_do():
    sheet = (_FRONTEND / "ReportSheet.tsx").read_text(encoding="utf-8")
    model = (_FRONTEND / "previewModel.ts").read_text(encoding="utf-8")

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
    import inspect

    from app.services import report_pdf

    build = inspect.getsource(report_pdf.PdfRenderer.build)
    write_to = _kotlin_function(pdf_kt, "writeTo")

    assert "for _ in range(8):" in build, "report_pdf no longer caps the measuring loop at eight"
    assert "for (iteration in 0 until 8)" in write_to, \
        "PdfWriter no longer caps the measuring loop at eight, and the server does"

    assert "signature in seen" in build, \
        "report_pdf no longer recognises a layout it has already measured"
    assert "seen.add(" in write_to, \
        "PdfWriter no longer recognises a layout it has already measured"

    assert build.count("self._run_pass(drawing=False)") == 2, (
        "report_pdf no longer measures once more after the loop with `_toc_source` untouched — "
        "the contents numbers would then belong to a layout that is not the one drawn"
    )
    assert write_to.count("runPass(drawing = false)") == 2, (
        "PdfWriter no longer measures once more after the loop with `tocEntries` untouched — "
        "the contents numbers would then belong to a layout that is not the one drawn"
    )
    reconcile_py = build[build.rindex("self._run_pass(drawing=False)"):]
    assert "_toc_source" not in reconcile_py, \
        "report_pdf re-adopts the contents after the reconciling pass"
    reconcile_kt = write_to[write_to.rindex("runPass(drawing = false)"):]
    assert "tocEntries" not in reconcile_kt, \
        "PdfWriter re-adopts the contents after the reconciling pass"

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
