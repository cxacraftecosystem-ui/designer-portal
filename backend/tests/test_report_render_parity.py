import re
import zipfile
from io import BytesIO

import pytest
from pypdf import PdfReader
from reportlab.pdfbase import pdfmetrics

from app.services import report_pdf
from app.services.report_docx import render_docx
from app.services.report_model import (
    DocumentBuilder,
    HeadingBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    TableColumn,
    TocBlock,
    runs_of,
)
from app.services.report_pdf import render_pdf

HEADER = (
    "Sambalpuri Bandha Ikat handloom weaving cluster — Bargarh, Odisha — Ministry of Textiles, "
    "Office of the Development Commissioner (Handicrafts)"
)
FOOTER = (
    "Design & Prototype Workshop — cluster development template v4 — workshop code "
    "OD/BGH/2026/017 — not for circulation outside the office of the Development Commissioner"
)

LONG_ANSWER = (
    "The designer recorded the weaver's own account of the tie-and-dye sequence in full. " * 70
)


@pytest.fixture(scope="module", autouse=True)
def _vera() -> None:
    import os

    regular, bold = report_pdf._vera_paths()
    previous = {name: os.environ.get(name)
                for name in ("REPORT_PDF_FONT", "REPORT_PDF_FONT_BOLD")}
    os.environ["REPORT_PDF_FONT"] = regular
    os.environ["REPORT_PDF_FONT_BOLD"] = bold
    bound = report_pdf.register_fonts(force=True)
    assert bound.regular.startswith("ReportCustom"), (
        f"the Vera override did not bind; the tests below would measure against {bound.regular} "
        f"and the font-dependent drift they exist for would hide behind it again"
    )
    yield
    for name, value in previous.items():
        if value is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = value
    report_pdf.register_fonts(force=True)


def _document(*, sections: int = 40, long_row: bool = True):
    builder = DocumentBuilder(meta=ReportMeta(
        title="Design & Prototype Workshop report",
        subtitle="Bargarh cluster",
        organisation="Office of the Development Commissioner (Handicrafts)",
        generated_at="2026-01-01T00:00:00Z",
        header_text=HEADER,
        footer_text=FOOTER,
    ))
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, sections + 1):
        builder.add(HeadingBlock(
            level=1, number=str(i), runs=runs_of(f"Section marker {i:03d}"), bookmark=f"h{i}",
        ))
        builder.add(ParagraphBlock(runs=runs_of("body " * 60)))
    if long_row:
        builder.add(TableBlock(
            columns=(TableColumn(header="Question", width_pct=30.0),
                     TableColumn(header="Answer", width_pct=70.0)),
            rows=((runs_of("Process, in the weaver's words"), runs_of(LONG_ANSWER)),),
        ))
    return builder.build()


def _measurable_names() -> dict[str, str]:
    names: dict[str, str] = {}
    for registered in report_pdf._REGISTERED:
        names[registered] = registered
        try:
            face = pdfmetrics.getFont(registered).face.name
        except Exception:  # noqa: BLE001 - a name that will not resolve cannot appear in a file
            continue
        names[face.decode() if isinstance(face, bytes) else str(face)] = registered
    return names


def _pdf_pieces(pdf: bytes):
    known = _measurable_names()
    unmeasured: set[str] = set()
    reader = PdfReader(BytesIO(pdf))
    pieces: list[tuple[int, str, float, float, float]] = []
    for number, page in enumerate(reader.pages, 1):

        def visit(text, _cm, tm, font_dict, font_size, number=number):
            if not text.strip():
                return
            drawn = text.replace("\n", "").replace("\r", "")
            base = str((font_dict or {}).get("/BaseFont", "")).lstrip("/").split("+")[-1]
            size = font_size or 10.0
            resolved = known.get(base) or known.get(base.rpartition("-")[0])
            if resolved is None:
                unmeasured.add(base)
                width = len(drawn) * size * 0.5
            else:
                width = pdfmetrics.stringWidth(drawn, resolved, size)
            pieces.append((number, drawn, tm[4], tm[4] + width, tm[5]))

        page.extract_text(visitor_text=visit)
    assert not unmeasured, (
        f"the widths below would be half-em guesses for {sorted(unmeasured)}, which is not a "
        f"measurement — every geometry assertion in this module would be about the guess"
    )
    box = reader.pages[0].mediabox
    return pieces, float(box.width), float(box.height), len(reader.pages)


def _pdf_text(pdf: bytes) -> str:
    reader = PdfReader(BytesIO(pdf))
    return " ".join(" ".join((page.extract_text() or "").split()) for page in reader.pages)


def _pdf_body_text(pieces, page_h: float) -> str:
    floor = 72.0
    ceiling = page_h - 25.0 * 72.0 / 25.4 + 2.0
    return " ".join(
        " ".join(text for _page, text, _x0, _x1, y in pieces if floor <= y <= ceiling).split()
    )


def _docx_parts(document) -> dict[str, str]:
    data, _dropped = render_docx(document, lambda _ref: None)
    archive = zipfile.ZipFile(BytesIO(data))
    return {name: archive.read(name).decode("utf-8")
            for name in ("word/document.xml", "word/header1.xml", "word/footer1.xml")}


def _words(xml: str) -> str:
    from html import unescape

    return " ".join(unescape(re.sub(r"<[^>]+>", " ", xml)).split())



def test_the_running_furniture_of_one_report_is_on_the_sheet_in_both_files():
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, page_w, page_h, pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    assert HEADER in _words(parts["word/header1.xml"]), \
        "the .docx running head lost the line the PDF is being measured against"
    assert FOOTER in _words(parts["word/footer1.xml"]), \
        "the .docx running foot lost the line the PDF is being measured against"

    overhangs = [(piece[3] - page_w, piece) for piece in pieces if piece[3] > page_w + 0.5]
    underhangs = [(-piece[2], piece) for piece in pieces if piece[2] < -0.5]
    below = [(-piece[4], piece) for piece in pieces if piece[4] < 0]
    above = [(piece[4] - page_h, piece) for piece in pieces if piece[4] > page_h]
    for label, offenders in (("past the right edge", overhangs),
                             ("off the left edge", underhangs),
                             ("below the bottom edge", below),
                             ("above the top edge", above)):
        if offenders:
            worst, piece = max(offenders)
            raise AssertionError(
                f"{len(offenders)} of {len(pieces)} drawn strings are {label} of a "
                f"{page_w:.1f}x{page_h:.1f} pt sheet; the worst is {piece[1]!r} on page "
                f"{piece[0]}, {worst:.1f} pt out. The .docx of the same document wraps it."
            )

    first_word = HEADER.split(maxsplit=1)[0]
    assert any(piece[1].startswith(first_word) and piece[0] > 1 for piece in pieces), \
        "the PDF drew no running head at all, so nothing above was actually tested"
    assert pages > 1, "a one-page document has no running furniture and proves nothing"



def test_both_files_number_their_pages_out_of_the_same_total():
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _page_w, _page_h, pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    footer = parts["word/footer1.xml"]
    assert "Page" in _words(footer) and " of " in _words(footer), \
        "the .docx running foot no longer says 'Page N of M'"
    assert "NUMPAGES" in footer, "the .docx running foot no longer resolves the total"

    by_page: dict[int, list[str]] = {}
    for number, text, _x0, _x1, y in pieces:
        if y < 72.0:
            by_page.setdefault(number, []).append(text)
    labelled = {}
    for number, tokens in by_page.items():
        found = re.search(r"Page\s*(\d+)\s*of\s*(\d+)", " ".join(tokens))
        if found:
            labelled[number] = (int(found.group(1)), int(found.group(2)))

    assert labelled, "no page of the PDF carries a 'Page N of M' label"
    assert set(labelled) == set(range(2, pages + 1)), (
        f"the PDF labels pages {sorted(labelled)} of a {pages}-page document; every page but the "
        f"cover must carry one, as every page but the cover does in the .docx"
    )
    for number, (printed, total) in sorted(labelled.items()):
        assert printed == number, f"page {number} of the PDF calls itself page {printed}"
        assert total == pages, (
            f"page {number} of the PDF says the document is {total} pages long and {pages} "
            f"came out — the drawing pass paginated differently from the pass that measured it"
        )



def test_neither_file_loses_a_word_of_a_cell_taller_than_the_page():
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _page_w, page_h, _pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    expected = " ".join(LONG_ANSWER.split())
    assert expected in _words(parts["word/document.xml"]), \
        "the .docx lost part of the long answer, which it has never done before"
    body = _pdf_body_text(pieces, page_h).replace("Question Answer ", "")
    assert expected in body, \
        "the PDF lost part of the long answer the .docx of the same document keeps in full"

    off_the_sheet = [piece for piece in pieces if piece[4] < 0]
    assert not off_the_sheet, (
        f"{len(off_the_sheet)} of {len(pieces)} drawn strings are below the bottom of the paper, "
        f"the lowest at y={min(piece[4] for piece in off_the_sheet):.1f} — the over-tall row was "
        f"not cut, and the .docx of the same document splits it"
    )



@pytest.mark.parametrize(
    ("label", "kwargs"),
    [
        ("a short report", {"sections": 3, "long_row": False}),
        ("a report whose contents runs to more than one page", {"sections": 60,
                                                                "long_row": False}),
        ("a report with a row taller than the page", {"sections": 40, "long_row": True}),
        ("a report long enough for a millimetre of per-page drift to cost a page",
         {"sections": 150, "long_row": False}),
    ],
)
def test_the_pdf_draws_exactly_as_many_pages_as_it_measured(label, kwargs, caplog):
    import logging

    caplog.set_level(logging.ERROR, logger="app.services.report_pdf")
    document = _document(**kwargs)
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    drawn = len(PdfReader(BytesIO(pdf)).pages)

    disagreements = [record.getMessage() for record in caplog.records
                     if "measuring pass measured" in record.getMessage()]
    assert not disagreements, (
        f"the two passes disagreed about the length of {label}: {disagreements}"
    )
    assert f"of {drawn}" in _pdf_text(pdf) or drawn == 1, \
        f"{label} came out {drawn} pages long and no running foot says so"
