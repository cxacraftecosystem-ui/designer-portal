"""The .docx and the PDF of ONE report, compared on what actually comes out of them.

``test_report_parity.py`` reads the two renderers' SOURCE and asserts they say the same things.
That catches a constant edited on one side, which is most of the drift — and it cannot catch the
drift that is not in a constant. Every divergence in the list this module was opened for was of
that second kind:

* the .docx wrapped the running head inside its header part and the PDF drew it with one
  ``drawString``, so the same ministry line was on the sheet in one file and 265 pt off the edge
  of the paper in the other;
* the .docx printed "Page N of M" from its PAGE and NUMPAGES fields and the PDF printed "Page N",
  so the two files of one download numbered their pages differently;
* the .docx split a table row taller than the page, because Word splits any ``<w:tr>`` without
  ``<w:cantSplit/>``, and the PDF drew the overflow at a negative y where no reader ever sees it.

None of those is a number one file has and the other has not. Each is only visible by GENERATING
BOTH FILES FROM ONE DOCUMENT AND LOOKING AT WHAT CAME OUT, which is what this module does: the
PDF through ``pypdf``'s text visitor, so every drawn string carries the coordinates it was drawn
at, and the .docx by reading the parts out of the zip. A reader holding both files and finding
them different stops believing either, so the assertions are written as things such a reader
could check.

── WHY THE FONT IS PINNED TO VERA ──────────────────────────────────────────────────────────────

``report_pdf`` resolves its face from the host, and the drift this module is looking for HIDES
BEHIND THAT. The level-1 heading rule — 1.2 mm applied while drawing and not while measuring —
passed on a Windows box under Nirmala UI and failed on a CI runner under DejaVu on the identical
commit, because whether a millimetre of accumulated drift moves a section across a page boundary
depends on where the text wraps, which depends on the face. So the tests here bind ReportLab's
vendored Vera, which is the face DejaVu was derived from and the only DejaVu-family metric this
repository can rely on being installed anywhere (there is no DejaVu on a stock Windows host, and
``report_pdf._vera_paths`` is how the module itself reaches the fallback). Pinning it also makes
the page counts below reproducible, which they are not under whatever the host happens to own.
"""

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

#: A real ministry running head. The one that was measured at 509.5 pt against a 453.5 pt text
#: column when the single ``drawString`` this used to be ran it off the sheet.
HEADER = (
    "Sambalpuri Bandha Ikat handloom weaving cluster — Bargarh, Odisha — Ministry of Textiles, "
    "Office of the Development Commissioner (Handicrafts)"
)
FOOTER = (
    "Design & Prototype Workshop — cluster development template v4 — workshop code "
    "OD/BGH/2026/017 — not for circulation outside the office of the Development Commissioner"
)

#: One LONG_TEXT answer of the size the registry permits. All thirty LONG_TEXT registry fields
#: carry ``max_length == 0``, so nothing upstream stops a designer writing one this long, and at
#: this length the row it sits in is taller than an A4 page.
LONG_ANSWER = (
    "The designer recorded the weaver's own account of the tie-and-dye sequence in full. " * 70
)


@pytest.fixture(scope="module", autouse=True)
def _vera() -> None:
    """Bind ReportLab's vendored Vera for this module, and put the host's face back after.

    ``register_fonts`` caches in a module global, so this has to be undone or every later test
    module in the same process would silently be measuring against Vera as well — which is the
    same class of accident as the one the fixture exists to defend against.
    """
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
    """One report, rendered to both surfaces. Long enough to have running furniture on it.

    The cover is page 1 and carries no furniture, so a document with one page proves nothing
    about a running head — hence forty sections rather than one.
    """
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
    """Every ``/BaseFont`` this process could meet, mapped to a name it can measure with.

    ── THE ESTIMATE THAT ABSORBS THE THING BEING MEASURED ─────────────────────────────────────

    A ``/BaseFont`` is not the name ``registerFont`` was given: ReportLab writes the face's own
    PostScript name and prefixes a subset tag, so a font bound as ``ReportCustom`` reaches the
    file as ``/AAAAAA+BitstreamVeraSans-Roman``. Resolving that back by string surgery on the
    name works only while the two happen to agree — which they do for Nirmala UI, whose face name
    IS ``NirmalaUI``, and do not for anything bound through ``REPORT_PDF_FONT``.

    When it fails there is no error: the measurement falls back to a half-em estimate, which for
    a dot leader is about a third again too wide and makes a leader that stops well inside the
    column look like 10.9 pt of overhang past the trim edge. That is the same shape as the defect
    these tests are looking for, so the fallback would be reporting one bug and hiding another.
    The mapping is therefore built from what ReportLab actually registered, by asking each bound
    font for the face name it will write into the file.
    """
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
    """Every drawn string with its box, plus the sheet's size and its page count."""
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
    """The drawn text with the running furniture taken out, in drawing order.

    A row taller than the page is CUT and continued overleaf, so the answer in it is interrupted
    by a running foot, a page number and a running head every time it crosses a boundary — which
    means the plain extraction cannot be searched for a sentence that spans a page. Dropping the
    two margin bands and keeping the order the renderer drew in leaves the body as one string,
    which is what a reader of the paper sees when they turn the page.
    """
    floor = 72.0                       # the running foot and the page number sit below this
    ceiling = page_h - 25.0 * 72.0 / 25.4 + 2.0   # the running head sits above the text column
    # Whitespace collapsed, because each piece carries its own trailing space: joining them with
    # another one puts a double space between every pair of words and nothing matches.
    return " ".join(
        " ".join(text for _page, text, _x0, _x1, y in pieces if floor <= y <= ceiling).split()
    )


def _docx_parts(document) -> dict[str, str]:
    data, _dropped = render_docx(document, lambda _ref: None)
    archive = zipfile.ZipFile(BytesIO(data))
    return {name: archive.read(name).decode("utf-8")
            for name in ("word/document.xml", "word/header1.xml", "word/footer1.xml")}


def _words(xml: str) -> str:
    """The visible text of a WordprocessingML part, whitespace collapsed.

    Entities are resolved, because ``report_docx._esc`` escapes the apostrophe and the ampersand
    and a reader of the opened document sees neither: comparing against the raw part would fail
    on every footer line containing a "&" and on every sentence with a possessive in it.
    """
    from html import unescape

    return " ".join(unescape(re.sub(r"<[^>]+>", " ", xml)).split())


# --------------------------------------------------------------------------------------
# The running head — wrapped in one file and off the sheet in the other
# --------------------------------------------------------------------------------------


def test_the_running_furniture_of_one_report_is_on_the_sheet_in_both_files():
    """The head and the foot are in the .docx header part AND inside the PDF's trim edges.

    Word wraps whatever is in ``word/header1.xml`` to the text column on its own, so the .docx
    has never been able to get this wrong. The PDF drew each of them with one ``drawString`` and
    could only get it right by accident. Asserting "the text is present" on the .docx and "no
    drawn string crosses the trim edge" on the PDF is the pair of statements that makes the two
    files the same document rather than two files that happen to contain the same words.
    """
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, page_w, page_h, pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    assert HEADER in _words(parts["word/header1.xml"]), \
        "the .docx running head lost the line the PDF is being measured against"
    assert FOOTER in _words(parts["word/footer1.xml"]), \
        "the .docx running foot lost the line the PDF is being measured against"

    # Every drawn string, furniture included, inside the paper. Reported with the worst offender
    # rather than as a bare False, because "something is off the page" is not actionable and
    # "the running head ends 265.0 pt past the right edge" is.
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

    # And the head really is being drawn — an empty header would satisfy every check above.
    first_word = HEADER.split(maxsplit=1)[0]
    assert any(piece[1].startswith(first_word) and piece[0] > 1 for piece in pieces), \
        "the PDF drew no running head at all, so nothing above was actually tested"
    assert pages > 1, "a one-page document has no running furniture and proves nothing"


# --------------------------------------------------------------------------------------
# The page number — "Page N of M" in one file and "Page N" in the other
# --------------------------------------------------------------------------------------


def test_both_files_number_their_pages_out_of_the_same_total():
    """"Page N of M" in the .docx and "Page N of M" in the PDF, with M the real page count.

    The .docx builds the label from PAGE and NUMPAGES and Word resolves both, so it has always
    said "of M". The PDF said "Page N", because a PDF cannot ask itself how long it is — and a
    reader holding both had nothing to tell them whether the PDF was the whole document or an
    extract. The measuring pass knows the total before anything is drawn, so it prints it now.

    M is checked against the number of pages that came out, not against the renderer's own idea
    of it: a total taken from the measuring pass is only right if the drawing pass agreed with
    the pass that measured it, and the whole class of defect this module exists for is the two
    passes disagreeing.
    """
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _page_w, _page_h, pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    footer = parts["word/footer1.xml"]
    assert "Page" in _words(footer) and " of " in _words(footer), \
        "the .docx running foot no longer says 'Page N of M'"
    assert "NUMPAGES" in footer, "the .docx running foot no longer resolves the total"

    # The label arrives as ONE drawn string — ``_draw_furniture`` places it with a single
    # ``drawRightString`` — while the footer line beside it goes through ``_draw_line``, which
    # draws a piece at a time so each piece can carry its own font and colour, and reaches the
    # extractor as one string per word. So the band below the text column is a mix, and the
    # pieces on a page are joined and searched rather than compared: the join is what lets the
    # footer's words sit beside the label, and it is also why this keeps working if reportlab or
    # pypdf ever splits the label too.
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
    # The cover carries none, by design (`titlePg` in the .docx, `_page <= 1` in the PDF).
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


# --------------------------------------------------------------------------------------
# A row taller than the page — split in one file and dropped off the other
# --------------------------------------------------------------------------------------


def test_neither_file_loses_a_word_of_a_cell_taller_than_the_page():
    """The .docx has always kept it, because Word splits a ``<w:tr>`` with no ``<w:cantSplit/>``.

    The PDF drew a row it could not fit inside a lock that suppressed every page break, so the
    remainder went to a negative y — present in the file, extractable as text, and outside the
    paper. The words matching is therefore not the assertion on its own; where they were drawn
    is. Both halves are checked here because either alone passes on a renderer that is wrong in
    the other way.
    """
    document = _document()
    pdf, _dropped = render_pdf(document, lambda _ref: None)
    pieces, _page_w, page_h, _pages = _pdf_pieces(pdf)
    parts = _docx_parts(document)

    expected = " ".join(LONG_ANSWER.split())
    assert expected in _words(parts["word/document.xml"]), \
        "the .docx lost part of the long answer, which it has never done before"
    # The column headings are re-drawn at the top of each continuation, which is the one thing
    # that legitimately interrupts the answer — the .docx asks Word for the same repetition with
    # `<w:tblHeader/>`. Taken out here so what is left is the cell's own words end to end.
    body = _pdf_body_text(pieces, page_h).replace("Question Answer ", "")
    assert expected in body, \
        "the PDF lost part of the long answer the .docx of the same document keeps in full"

    off_the_sheet = [piece for piece in pieces if piece[4] < 0]
    assert not off_the_sheet, (
        f"{len(off_the_sheet)} of {len(pieces)} drawn strings are below the bottom of the paper, "
        f"the lowest at y={min(piece[4] for piece in off_the_sheet):.1f} — the over-tall row was "
        f"not cut, and the .docx of the same document splits it"
    )


# --------------------------------------------------------------------------------------
# The class itself — the two passes agreeing, checked on the rendered file
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("label", "kwargs"),
    [
        ("a short report", {"sections": 3, "long_row": False}),
        ("a report whose contents runs to more than one page", {"sections": 60,
                                                                "long_row": False}),
        ("a report with a row taller than the page", {"sections": 40, "long_row": True}),
        # THE ONLY ONE OF THE FOUR THAT IS SENSITIVE, and the reason it is here. The three above
        # were written first and all three passed with the 6 mm running-head clearance put back
        # behind `self._drawing`: the drift is per PAGE, so a thirteen-page report accumulates
        # 78 mm of it and never crosses a boundary. At 150 sections it does — measured with the
        # clearance re-guarded, the measuring pass said 31 pages and the drawing pass produced
        # 32. A detector for a cumulative error has to be given something to accumulate over.
        ("a report long enough for a millimetre of per-page drift to cost a page",
         {"sections": 150, "long_row": False}),
    ],
)
def test_the_pdf_draws_exactly_as_many_pages_as_it_measured(label, kwargs, caplog):
    """THE GENERAL FORM OF THE THREE DEFECTS THIS RENDERER HAS SHIPPED.

    A running-head clearance, a level-1 heading rule and a keepNext reservation were each applied
    on one pass and not the other. All three had the same signature — the drawing pass laid the
    document out longer than the measuring pass had — and none of them raised anything, produced
    a broken file, or was visible anywhere except in a contents page whose numbers were one or
    two too low. ``build`` now says so in the log when the two counts differ, and this asserts
    that it has nothing to say, on a rendered document rather than on a reading of the source.

    Parametrised over the three shapes that have actually broken it: a document too short for the
    contents to matter, one whose contents is long enough to move the body, and one containing a
    block that fits on no page at all.
    """
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
    # And the total the running foot prints is that same count — which is what makes the log
    # check above worth anything, since a renderer that never measured would also never disagree.
    assert f"of {drawn}" in _pdf_text(pdf) or drawn == 1, \
        f"{label} came out {drawn} pages long and no running foot says so"
