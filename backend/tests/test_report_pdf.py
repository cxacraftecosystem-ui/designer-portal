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
    reader = PdfReader(BytesIO(pdf))
    return [" ".join((page.extract_text() or "").split()) for page in reader.pages]



def _long_document(headings: int = 150):
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents", depth=1))
    for i in range(1, headings + 1):
        builder.add(HeadingBlock(
            level=1, runs=runs_of(f"Section marker {i:03d}"), number=str(i), bookmark=f"h{i}",
        ))
        builder.add(ParagraphBlock(runs=runs_of("body " * 80)))
    return builder.build()


def test_every_page_number_the_contents_prints_is_the_page_the_section_is_on():
    pdf, _dropped = render_pdf(_long_document(), lambda _ref: None)
    pages = _pages(pdf)

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
    builder = DocumentBuilder(meta=_meta())
    builder.add(TocBlock(title="Contents"))
    builder.add(ParagraphBlock(runs=runs_of("No sections.")))
    pdf, _dropped = render_pdf(builder.build(), lambda _ref: None)
    assert pdf.startswith(b"%PDF")
    assert "Contents" in " ".join(_pages(pdf))



def _wrapped_runs(document) -> list[tuple[Run, ...]]:
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
    run = _rebuilt_table_header()
    assert run.bold is True, "the header must still be bold"
    assert run.underline is False, "a bold header must not acquire an underline"
    assert run.strike is False, "nor a strikethrough"


def test_a_table_header_keeps_the_script_that_decides_its_typeface():
    assert _rebuilt_table_header().script is Script.ODIA


def test_an_italic_quote_keeps_its_script_underline_and_strike():
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



def test_the_runtime_image_installs_the_fonts_a_report_needs():
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
    paths = [face.regular for face in report_pdf._script_candidates(script)]
    assert paths, f"{script.value} has no candidate face at all"
    stem = report_pdf._NOTO_STEM[script]
    assert any(f"/{stem}-Regular.ttf" in path for path in paths), (
        f"{script.value} cannot reach {stem}, so it would be drawn by another script's face"
    )


def test_the_symbol_bucket_leads_with_a_face_that_carries_dingbats():
    paths = [face.regular for face in report_pdf._script_candidates(Script.OTHER)]
    assert paths
    assert "DejaVuSans" in paths[0], "DejaVu is the one bundled face with the tick and the cross"


def test_a_face_that_cannot_draw_the_rupee_sign_says_so():
    regular, _bold = report_pdf._vera_paths()
    name = report_pdf._register_one("VeraCoverageProbe", regular, 0)
    assert name, "ReportLab vendors Vera, so this cannot fail"

    fonts = report_pdf.FontSet(name, name, {})
    assert [character for character, _why in fonts.missing_glyphs] == ["₹", "✓", "✗"]
    assert fonts.covers_indic is False
    assert Script.ODIA in fonts.missing_scripts


def test_a_font_this_process_cannot_inspect_is_not_reported_as_missing():
    assert report_pdf._drawable("Helvetica", "₹") is None
    assert report_pdf._drawable("NoSuchFontWasEverRegistered", "₹") is None
    assert report_pdf.FontSet("Helvetica", "Helvetica-Bold", {}).missing_glyphs == []


def test_the_designer_is_told_before_they_attach_the_file():
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
