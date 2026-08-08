"""The server PDF writer, checked on the FILE rather than on the renderer's own state.

Every test here asserts something an officer holding the printed submission can see. That is not
a stylistic preference: both failures this file was opened for produced a PDF that opens
perfectly, raises nothing, and is wrong — a contents page whose every number is ten pages short,
and a table header that is underlined in the download and not on the phone. Neither is visible
from any assertion about the model, so the bytes are read back with ``pypdf`` and the drawing
operators are read directly out of the content stream.

``extract_text`` returns one word per line here, because ``_draw_lines`` draws every wrapped
token with its own ``drawString``. Whitespace is therefore normalised before anything is matched
— see ``_pages``.
"""

import pathlib
import re
from io import BytesIO

import pytest
from pypdf import PdfReader

from app.services import report_pdf
from app.services.report_model import (
    Align,
    DocumentBuilder,
    HeadingBlock,
    ParagraphBlock,
    ParaStyle,
    ReportMeta,
    Run,
    Script,
    TableBlock,
    TableColumn,
    TocBlock,
    runs_of,
)
from app.services.report_pdf import render_pdf


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "generated_at": "2026-01-01T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


def _pages(pdf: bytes) -> list[str]:
    """Every page's text with runs of whitespace collapsed to one space."""
    reader = PdfReader(BytesIO(pdf))
    return [" ".join((page.extract_text() or "").split()) for page in reader.pages]


# --------------------------------------------------------------------------------------
# The table of contents
# --------------------------------------------------------------------------------------


def _long_document(headings: int = 150):
    """A document whose contents page runs to several pages, which is the whole point.

    A one-page contents hides this bug: the error is exactly the number of pages the contents
    occupies beyond the single line the measuring pass believed it needed, so a short report is
    off by nothing and a real 167-page one is off by ten.
    """
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, headings + 1):
        builder.add(HeadingBlock(
            level=1, runs=runs_of(f"Section marker {i:03d}"), number=str(i), bookmark=f"h{i}",
        ))
        builder.add(ParagraphBlock(runs=runs_of("body " * 80)))
    return builder.build()


def test_every_page_number_the_contents_prints_is_the_page_the_section_is_on():
    """THE REGRESSION THIS FILE WAS OPENED FOR.

    ``build`` cleared ``_toc_entries`` at the top of each measuring pass and ``_block_toc`` read
    that same list — but the contents block is laid out BEFORE any heading has run, so on every
    pass it saw an empty list, measured itself as one title line, and the three iterations the
    comment describes as "feeding the first pass's entries back in" fed nothing back. The DRAW
    pass then inherited the last measurement's entries and printed the real contents, so every
    number in it had been measured against a contents page that did not exist.

    In the 167-page report for the flagship workshop all 346 resolvable entries were wrong — 303
    off by nine and 43 by ten: "Certification .... 158" for a section on page 167 — while the
    PDF's own bookmark outline, built during the draw pass, was right. The printed contents of a
    ministry submission contradicted the sidebar of the same document, and an officer using it
    landed ten pages short every time.
    """
    pdf, _dropped = render_pdf(_long_document(), lambda _ref: None)
    pages = _pages(pdf)

    # The contents pages are the only ones carrying dot leaders, and the block ends with a page
    # break, so the body starts on the page after the last of them.
    toc_indexes = [i for i, text in enumerate(pages) if "........." in text]
    assert toc_indexes, "the contents page did not render at all"
    assert len(toc_indexes) > 1, "this document must produce a MULTI-page contents to be a test"
    first_body = max(toc_indexes) + 1
    contents = " ".join(pages[i] for i in toc_indexes)

    checked = 0
    for i in (1, 2, 40, 90, 150):
        label = f"{i}. Section marker {i:03d}"
        printed = re.search(re.escape(label) + r" (\d+)", contents)
        assert printed, f"the contents does not list {label!r}"
        # Searched only AFTER the contents, or the entry in the contents itself would match.
        actual = next(
            (j + 1 for j in range(first_body, len(pages))
             if f"Section marker {i:03d}" in pages[j]),
            None,
        )
        assert actual, f"{label!r} never appears in the body"
        assert int(printed.group(1)) == actual, (
            f"the contents sends a reader to page {printed.group(1)} for {label!r}, "
            f"which is on page {actual}"
        )
        checked += 1
    assert checked == 5


def test_a_document_with_no_headings_still_renders_its_contents_page():
    """The carried-over entries must not become a requirement. A one-stage report, or a template
    whose sections are all suppressed, has nothing to list and still has to produce a file."""
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents"))
    builder.add(ParagraphBlock(runs=runs_of("No sections.")))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)
    assert pdf.startswith(b"%PDF")
    assert "Contents" in " ".join(_pages(pdf))


# --------------------------------------------------------------------------------------
# Runs rebuilt for a header or an italic style
# --------------------------------------------------------------------------------------


def _wrapped_runs(document) -> list[tuple[Run, ...]]:
    """Every run sequence the renderer hands to ``_wrap`` while laying ``document`` out.

    Read off the renderer rather than off the page because underline and script have become a
    stroked line and a font choice by the time they reach the paper: catching a field that was
    DROPPED on the way needs the run itself.
    """
    from app.services import report_pdf as module

    captured: list[tuple[Run, ...]] = []
    original = module.PdfRenderer._wrap

    def spy(self, runs, width, size, *args, **kwargs):
        captured.append(tuple(runs))
        return original(self, runs, width, size, *args, **kwargs)

    module.PdfRenderer._wrap = spy
    try:
        render_pdf(document, lambda _ref: None)
    finally:
        module.PdfRenderer._wrap = original
    return captured


#: An Odia column heading — "quantity". A header is built from the column's plain string, so its
#: script is whatever ``runs_of`` derives; that is exactly the field the positional rebuild threw
#: away.
_ODIA_HEADER = "ପରିମାଣ"


def _rebuilt_table_header() -> Run:
    builder = DocumentBuilder(meta=_meta())
    builder.add(TableBlock(
        columns=(TableColumn(header=_ODIA_HEADER, width_pct=100.0),),
        rows=((runs_of("row"),),),
    ))
    captured = _wrapped_runs(builder.build())
    return next(runs for runs in captured if runs and runs[0].text == _ODIA_HEADER)[0]


def test_a_table_header_is_made_bold_without_acquiring_an_underline():
    """``Run(r.text, True, r.italic, r.script, r.color)`` — what this line used to say.

    ``Run``'s fields are (text, bold, italic, underline, strike, script, color, …), so the fifth
    and sixth positional arguments landed on the wrong ones: the script into ``underline``, which
    is a str-Enum and so true for every script there is, and the colour into ``strike``. Every
    table header in every server-generated PDF was underlined, where the .docx writes the same
    run with ``<w:b/>`` and no ``<w:u>`` — and ``PdfWriter.kt`` uses ``it.copy(bold = true)`` and
    was never wrong, so the same table looked different in the office's download and on the
    phone.
    """
    run = _rebuilt_table_header()
    assert run.bold is True, "the header must still be bold"
    assert run.underline is False, "a bold header must not acquire an underline"
    assert run.strike is False, "nor a strikethrough"


def test_a_table_header_keeps_the_script_that_decides_its_typeface():
    """The dropped field with the worst consequence: ``script`` fell back to LATIN, so
    ``FontSet.pick`` sent an Odia column heading to the Latin face and it printed as boxes above
    a column of Odia cells that printed correctly."""
    assert _rebuilt_table_header().script is Script.ODIA


def test_an_italic_quote_keeps_its_script_underline_and_strike():
    """The second site, and the one ``report_docx`` had already corrected with ``replace`` while
    this renderer was left alone: a QUOTE or CAPTION carrying a colour was struck through, and an
    Odia pull-quote lost the script that is the only thing making it render as words."""
    quote = (Run(text="ବନ୍ଧା", script=Script.ODIA, color="112233", underline=True),)
    builder = DocumentBuilder(meta=_meta())
    builder.add(ParagraphBlock(runs=quote, style=ParaStyle.QUOTE, align=Align.LEFT))

    captured = _wrapped_runs(builder.build())
    run = next(runs for runs in captured if runs and runs[0].text == "ବନ୍ଧା")[0]
    assert run.italic is True, "the quote style must still be italic"
    assert run.script is Script.ODIA
    assert run.color == "112233"
    assert run.strike is False, "the colour must not have landed in `strike`"
    assert run.underline is True, "an underline the author asked for must survive"


# --------------------------------------------------------------------------------------
# The faces the server draws with
# --------------------------------------------------------------------------------------


def test_the_runtime_image_installs_the_fonts_a_report_needs():
    """THE CRITICAL ONE, and a property of the Dockerfile rather than of any Python.

    The deployed image shipped with NO fonts at all — ``ls /usr/share/fonts`` in the running
    container answered "No such file or directory" — so every Debian path in ``_candidates`` and
    ``_script_candidates`` missed and the renderer fell through to ReportLab's vendored Vera,
    which has no rupee sign, no tick, no cross and no Indic coverage whatsoever. One workshop's
    167-page PDF carried 1,031 empty boxes: the craft's name in Odia on page 1, "unit realisation
    stands at □2,800 to □6,500" on page 14, a quality checklist on page 134 in which every line
    began with a box. The .docx of the same workshop carried all 501 rupee signs, 46 ticks, 14
    crosses and 467 Odia codepoints correctly.

    Checked on the RUNTIME stage's apt line specifically: installing them in the builder would
    produce a green test and an unchanged image.
    """
    dockerfile = (
        pathlib.Path(__file__).resolve().parents[1] / "Dockerfile"
    ).read_text(encoding="utf-8")
    runtime = dockerfile.split("AS runtime", 1)
    assert len(runtime) == 2, "the runtime stage is not named the way this test expects"
    for package in ("fonts-noto-core", "fonts-dejavu-core"):
        assert package in runtime[1], (
            f"{package} is not installed in the runtime stage; the deployed PDF prints boxes"
        )


@pytest.mark.parametrize("script", sorted(report_pdf._NOTO_STEM, key=lambda s: s.value))
def test_every_indic_script_can_reach_a_face_of_its_own(script):
    """ONE FACE PER SCRIPT, NOT ONE "COMPLEX" FACE FOR ALL OF THEM.

    The candidates used to be a single list, and the first entry that bound won for every
    non-Latin run in the document. On Debian that list met ``NotoSansDevanagari`` before
    ``NotoSansOriya``, so every Odia character in the report — the craft name on the cover of a
    workshop held in Bargarh — went to a Devanagari face with no Odia glyphs and printed as a
    box, on a server that would have reported itself as Indic-capable.

    No face in ``fonts-noto-core`` carries both, so no ordering of one list could have been
    right.
    """
    paths = [face.regular for face in report_pdf._script_candidates(script)]
    assert paths, f"{script.value} has no candidate face at all"
    stem = report_pdf._NOTO_STEM[script]
    assert any(f"/{stem}-Regular.ttf" in path for path in paths), (
        f"{script.value} cannot reach {stem}, so it would be drawn by another script's face"
    )


def test_the_symbol_bucket_leads_with_a_face_that_carries_dingbats():
    """``Script.OTHER`` is not an Indic script: it is where ``split_by_script`` puts the ✓ and ✗
    that stage 12's own help text tells a designer to type ("One check per line; prefix with ✓ or
    ✗"). Those are Dingbats, which no Noto TEXT face and no Nirmala UI carries — so routing them
    to the "complex" face turned a designer following the instructions into a page of boxes."""
    paths = [face.regular for face in report_pdf._script_candidates(Script.OTHER)]
    assert paths
    assert "DejaVuSans" in paths[0], "DejaVu is the one bundled face with the tick and the cross"


def test_a_face_that_cannot_draw_the_rupee_sign_says_so():
    """The detection that turns a silent 1,031-box PDF into a sentence the designer reads.

    Vera is the fallback the fontless image actually used, so it is the honest subject here: it
    has none of the three characters and none of the nine scripts, and every one of them must be
    named rather than merely logged.
    """
    regular, _bold = report_pdf._vera_paths()
    name = report_pdf._register_one("VeraCoverageProbe", regular, 0)
    assert name, "ReportLab vendors Vera, so this cannot fail"

    fonts = report_pdf.FontSet(name, name, {})
    assert [character for character, _why in fonts.missing_glyphs] == ["₹", "✓", "✗"]
    assert fonts.covers_indic is False
    assert Script.ODIA in fonts.missing_scripts


def test_a_font_this_process_cannot_inspect_is_not_reported_as_missing():
    """An UNKNOWN must never be reported as a missing glyph, or a deployment would be warned
    about characters that print perfectly. One of the standard-14 Type 1 fonts carries no cmap
    this code can read."""
    assert report_pdf._drawable("Helvetica", "₹") is None
    assert report_pdf._drawable("NoSuchFontWasEverRegistered", "₹") is None
    assert report_pdf.FontSet("Helvetica", "Helvetica-Bold", {}).missing_glyphs == []


def test_the_designer_is_told_before_they_attach_the_file():
    """The warning has to reach the RESPONSE, not only the log. ``X-Report-Warnings`` carried six
    warnings on the tofu report and not one of them was about fonts, so the designer attached it
    to a ministry submission with no reason to look."""
    from app.services import design_workshops

    regular, _bold = report_pdf._vera_paths()
    name = report_pdf._register_one("VeraWarningProbe", regular, 0)

    cached = report_pdf._CACHED
    report_pdf._CACHED = report_pdf.FontSet(name, name, {})
    try:
        warnings = design_workshops._font_warnings()
    finally:
        report_pdf._CACHED = cached

    assert any("₹" in w for w in warnings), "the rupee sign must be named"
    assert any("Odia" in w for w in warnings), "and so must the script the craft name is in"
    assert all("Word document" in w for w in warnings), (
        "every one of these must point at the file that IS correct"
    )
